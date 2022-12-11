package network

import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver

import scala.collection.mutable.Map
import scala.concurrent.{ExecutionContext, Promise}
import java.util.logging.Logger
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Status}
import message.connection._
import message.utils._
import utils._
import module._

import java.io.File
import scala.annotation.tailrec
import scala.io.Source
import java.util.concurrent.TimeUnit


class ClientData (
                   val inputDirPaths: Seq[String],
                   val outputDirPath: String,
                   val masterIP: String,
                   val masterPort: Int,
                   val shuffleServerIP: String,
                   val shuffleServerPort: Int // 30040?
                 ) {}

class NetworkClient(clientData: ClientData) {
  val logger = Logger.getLogger(classOf[NetworkClient].getName)

  val channel:ManagedChannel = ManagedChannelBuilder.forAddress(clientData.masterIP, clientData.masterPort).usePlaintext().build
  val blockingStub: ConnectGrpc.ConnectBlockingStub = ConnectGrpc.blockingStub(channel)
  val asyncStub: ConnectGrpc.ConnectStub = ConnectGrpc.stub(channel)

  var workerId: Int = -1 // id
  val workers = Map[Int, WorkerData]()
  var totalWorkerNum: Int = -1

  val sampleLinesNum = 10000 // sample Line 수 결정 필요

  // directory paths
  var sampleDir: String = null;
  var partitionedDir: String = null;
  var shuffledDir: String = null;
  var subpartitionedDir: String = null;

  // shuffleHandling
  var fileServer: FileServer = null
  def requestStart(): Unit = {
    logger.info("requestStart(): sending startRequest to Master")
    val startRequest = new StartRequest(clientData.shuffleServerIP, clientData.shuffleServerPort)
    val startResponse = blockingStub.start(startRequest)
    workerId = startResponse.workerId
    sampleDir = FileIO.createDir("sampleDir")
    logger.info("requestStart(): done!")
  }
  def sample(): Unit = {
    logger.info("sample(): sampling...")
    logger.info(clientData.inputDirPaths.head)
    Sampler.sample(clientData.inputDirPaths.head, sampleDir, sampleLinesNum)
    logger.info("sample(): done!")
  }
  def requestSample(promise: Promise[Unit]): Unit = {
    logger.info("requestSample(): sending sampleRequest with sample to Master...")
    val responseObserver = new StreamObserver[SampleResponse]() {
      override def onNext(sampleResponse: SampleResponse): Unit = {
        if(sampleResponse.status==Stat.SUCCESS) {
          promise.success()
        }
      }
      override def onCompleted(): Unit = {
        logger.info("requestSample(): done!")
      }
      override def onError(throwable: Throwable): Unit = {
        val errorStatus = Status.fromThrowable(throwable)
        logger.warning(s"requestSample(): failed with: ${errorStatus}")
        promise.failure(throwable)
      }
    }

    val requestObserver = asyncStub.sample(responseObserver)
    try {
      val sampleFile = FileIO.getFile(sampleDir, "sample").head
      val sampleSource = Source.fromFile(sampleFile)
      val sampleLines = sampleSource.getLines
      val inputFileNum = {
        val inputFilesLengthSeq = for {
          inputDirPath <- clientData.inputDirPaths
        } yield {
          val inputDir = new File(inputDirPath)
          assert(inputDir.isDirectory)
          inputDir.listFiles.length
        }
        inputFilesLengthSeq.sum
      }
      for (sampleLine <- sampleLines) {
        val request = SampleRequest(workerId = workerId, inputFileNum=inputFileNum, data = ByteString.copyFromUtf8(sampleLine+"\r\n"))
        requestObserver.onNext(request)
      }
      sampleSource.close
    } catch {
      case ex: RuntimeException => {
        requestObserver.onError(ex)
        throw ex
      }
    }

    requestObserver.onCompleted
  }

  @tailrec
  final def requestDivide(): Unit = {
    logger.info("requestDivide(): sending divideRequest to Master...")
    val divideRequest = new DivideRequest(workerId)
    val divideResponse = blockingStub.divide(divideRequest)
    divideResponse.status match {
      case Stat.SUCCESS => {
        totalWorkerNum = divideResponse.workerNum
        var counter = 0
        for (worker <- divideResponse.workers) {
          workers(worker.workerId) = WorkerData.MessageToWorkerData(worker)
          counter = counter + 1
        }
        assert(counter == totalWorkerNum)
        logger.info("requestDivide(): done!")
      }
      case Stat.FAILURE => {
        logger.warning("requestDivide(): divideResponse's Stat is FAILURE")
        throw new Exception
      }
      case _ => {
        Thread.sleep(5000) // 5초 후 재시도
        requestDivide
      }
    }
  }
  def partition(): Unit = {
    logger.info("partition(): partitioning...")
    partitionedDir = FileIO.createDir("partitionedDir")
    val partitioningPoints = (for {
      (id, worker) <- workers } yield { id -> worker.mainRange}).toMap
    Partitioner.partition(clientData.inputDirPaths, partitionedDir, partitioningPoints)
    logger.info("partition(): done!")
  }

  def startFileServer(): Unit = {
    logger.info("startFileServer(): starting FileServer...")
    shuffledDir = FileIO.createDir("shuffledDir")
    fileServer = new FileServer(ExecutionContext.global, clientData.shuffleServerPort, workerId, shuffledDir)
    fileServer.start
    logger.info("startFileServer(): done!")
  }

  @tailrec
  final def requestShuffle(): Unit = {
    logger.info("requestShuffle(): sending startShuffleRequest to Master...")
    val startShuffleRequest = new StartShuffleRequest(workerId)
    val startShuffleResponse = blockingStub.startShuffle(startShuffleRequest)
    startShuffleResponse.status match {
      case Stat.SUCCESS => {
        logger.info("requestShuffle(): done!")
      }
      case Stat.FAILURE => {
        logger.info("requestShuffle(): startShuffleResponse's Stat is FAILURE")
        throw new Exception
      }
      case _ => {
        Thread.sleep(5000) // 5초 후 재시도
        requestShuffle
      }
    }
  }
  def shuffle(): Unit = {
    logger.info("shuffle(): shuffling...")
    // Worker 본인을 위한 파일 shuffledDir로 옮기기
    val partitionedFileListForMe = FileIO.getFile(partitionedDir, s"partition-$workerId")
    for (file <- partitionedFileListForMe) {
      val shuffledFilePath = file.getAbsolutePath.replaceFirst("partitionedDir", "shuffledDir").replaceFirst(s"partition-$workerId", s"shuffle-$workerId")
//      logger.info(shuffledFilePath)
      val shuffledFile = new File(shuffledFilePath)
      assert(!shuffledFile.exists)
      file.renameTo(shuffledFile) // 이때 shufflledDir 가 존재하지 않는다면 에러가 발생함!!
    }
    logger.info("shuffle(): self rename done")

    // 다른 Worker 들에게 partitionedFile 보내주기
    val workerIdList = (1 to totalWorkerNum).toList
    for {
      wId <- workerIdList
    } {
      if(wId!=workerId) {
        logger.info(s"shuffle(): sending partitioned file from ${workerId} to ${wId}")
        var fileClient: FileClient = null
        try {
          val worker = workers(wId)
          fileClient = new FileClient(worker.ip, worker.port, workerId, partitionedDir)
          fileClient.shuffleWithAllReceiver(wId) // 은하가 구현해 놓은 fileClient의 함수 이용
        } finally {
          if (fileClient != null) { fileClient.shutdown }
        }
      }
    }
    logger.info("shuffle(): done!")
  }

  @tailrec
  final def requestSort(): Unit = {
    logger.info("reuqestSort(): sending sortRequest to Master...")
    val sortRequest = SortRequest(workerId)
    val sortResponse = blockingStub.sort(sortRequest)
    sortResponse.status match {
      case Stat.SUCCESS => {
        logger.info("requestSort(): done!")
      }
      case Stat.FAILURE => {
        logger.info("requestSort(): sortResponse's Stat is FAILURE")
        throw new Exception
      }
      case _ => {
        Thread.sleep(5000) // 5초 후 재시도
        requestSort
      }
    }
  }
  def stopFileServer(): Unit = {
    logger.info("stopFileServer(): stopping FileServer...")
    if (fileServer != null) {
      fileServer.shutdownServer
      fileServer = null
    }
    logger.info("stopFileServer(): done!")
  }
  def subPartition(): Unit = {
    logger.info("subPartition(): subpartitioning...")
    subpartitionedDir = FileIO.createDir("subpartitionedDir")
    Partitioner.subPartition(shuffledDir, subpartitionedDir, workers(workerId).subRange)
    logger.info("subPartition(): done!")
  }
  def sort(): Unit = {
    logger.info("sort(): sorting...")
//    logger.info(clientData.outputDirPath)
    // 기존에 이전 테스트 output 파일들 존재하는 경우 output 파일들 삭제
    val outputDir = new File(clientData.outputDirPath)
    assert(outputDir.isDirectory)
    for (outputFile <- FileIO.getFile(clientData.outputDirPath, null)) {
      assert(outputFile.isFile)
      outputFile.delete
    }
      // 그 다음에 sort 시작~
    Sorter.sort(subpartitionedDir, clientData.outputDirPath)
    logger.info("sort(): done!")
  }
  def shutdown(): Unit = {
    logger.info("shutdown(): When we pull up, you know it's a shutdown...")
    if (fileServer != null) {
      fileServer.shutdownServer
      fileServer = null
    }

    logger.info("shutdown(): let's check each working dir path")
    logger.info(s"shutdown(): sampleDir: ${sampleDir}")
    logger.info(s"shutdown(): partitionedDir: ${partitionedDir}")
    logger.info(s"shutdown(): shuffledDir: ${shuffledDir}")
    logger.info(s"shutdown(): subpartitionedDir: ${subpartitionedDir}")
//    if (sampleDir != null) {
//      FileIO.deleteDir(sampleDir)
//    }
//    if (partitionedDir != null) {
//      FileIO.deleteDir(partitionedDir)
//    }
//    if (shuffledDir != null) {
//      FileIO.deleteDir(shuffledDir)
//    }
//
//    if (subpartitionedDir != null) {
//      FileIO.deleteDir(subpartitionedDir)
//    }


    if (workerId != -1) {
      val terminateRequest = new TerminateRequest(workerId)
      val terminateResponse = blockingStub.terminate(terminateRequest)
      workerId = -1
      channel.shutdown.awaitTermination(5000, TimeUnit.MILLISECONDS)
    }
    logger.info("shutdown(): Keep watching me shut you down!!!")
  }
}
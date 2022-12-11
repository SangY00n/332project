package network

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.Map
import scala.util.{Failure, Success}

import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.io.FileOutputStream
import java.net._

import io.grpc.stub.StreamObserver
import io.grpc.{Server, ServerBuilder, Status}
import message.connection._
import message.utils.{Stat, workerInfo}
import utils._
import module.Divider

class NetworkServer(executionContext: ExecutionContext, port:Int, workerNum: Int) { self =>
  require(workerNum > 0, "The number of worker must be positive")

  val logger = Logger.getLogger(classOf[NetworkServer].getName)

  var server: Server = null
  var state: MasterState = MINIT
  var serverDir: String = null

  val workers = Map[Int, WorkerData]()

  /*--------------method for Master lifecycle-------------------*/
  def open(): Unit = {
    server = ServerBuilder.forPort(port)
      .addService(ConnectGrpc.bindService(new ConnectionImpl, executionContext))
      .build
      .start
    logger.info("Server started, listening on " + port)
    println(s"${InetAddress.getLocalHost.getHostAddress}:${port}")
    sys.addShutdownHook {
      self.shutdownServer()
      logger.info("NetworkServer shut down")
    }
    if (serverDir == null) {
      serverDir = FileIO.createDir("/master")
    }
  }

  def shutdownServer(): Unit = {
    if (server != null) {
      server.shutdown
      if(!server.awaitTermination(5, TimeUnit.SECONDS)) {
        server.shutdownNow() //fail shutdown while 5 seconds, shutdown now
      }
    }
    //if (serverDir != null) FileIO.deleteDir(serverDir)
  }

  def waitUntilShutdown(): Unit = {
    if (server != null) {
      server.awaitTermination
    }
  }
//Check all workers state. Use this function in sample, startShuffle, sort, terminate
  def checkWorkersState(masterState: MasterState, workerState: WorkerState): Boolean = {
    workers.synchronized {
      if (state == masterState && workers.size == workerNum && workers.forall { case (_, worker) => worker.state == workerState }) true
      else false
    }
  }

  /*--------------method for ConnectionImpl method-------------------*/
  class ConnectionImpl() extends ConnectGrpc.Connect {
    override def start(request: StartRequest): Future[StartResponse] = {
      assert(state == MINIT)
      logger.info(s"[start] Worker ${request.ip}:${request.port} send StartRequest")
      workers.synchronized {
        if (workers.size < workerNum) {
          workers(workers.size + 1) = new WorkerData(workers.size + 1, request.ip, request.port)
          if (workers.size == workerNum) {
            state = MSTART
          }
          logger.info(s"[start] Send StartResponse to Worker${workers.size}")
          Future.successful(new StartResponse(workers.size))
        } else {
          Future.failed(new Exception("[start] there are too many workers"))
        }
      }
    }

    override def sample(request: StreamObserver[SampleResponse]): StreamObserver[SampleRequest] = {
      assert(serverDir != null)
      assert(state == MINIT || state == MSTART)
      logger.info("[sample] Worker send sample")
      new StreamObserver[SampleRequest] {
        var id = -1
        var fileNum = 0
        var file: FileOutputStream = null
        override def onNext(value: SampleRequest): Unit = {
          id = value.workerId
          fileNum = value.inputFileNum
          //1. make a file to write the sample data in serverDir(: ./master/)
          if (file == null) {
            file = new FileOutputStream(FileIO.createFile(serverDir, s"sample_$id"))
          }
          //2. write sample data of value(the request) to sample_00
          value.data.writeTo(file)
          file.flush
        }
        override def onError(t: Throwable): Unit = {
          logger.warning(s"[sample] Worker $id fail to send a sample data ${Status.fromThrowable(t)}")
          throw t
        }
        override def onCompleted(): Unit = {
          logger.info(s"[sample] Worker $id done")
          file.close
          request.onNext(new SampleResponse(status = Stat.SUCCESS))
          request.onCompleted()
          workers.synchronized {
            workers(id).state = WSAMPLE
            workers(id).fileNum = fileNum
          }

          /*------Start dividing: make ranges------------*/
          if(checkWorkersState(MSTART, WSAMPLE)) {
            logger.info(s"[divide] All workers send sample data so start dividing")
            val future = Future {
              val fileRangeNum = workers.map{case (id, worker) => worker.fileNum}.sum / workerNum
              val ranges = Divider.getRange(serverDir, workerNum, fileRangeNum)
              workers.synchronized {
                for {
                  (id, worker) <- workers
                } {
                  worker.mainRange = ranges(id - 1)._1
                  worker.subRange = ranges(id - 1)._2
                }
              }
            }
            future.onComplete{
              case Success(value) => {
                state = MDIVIDE
                logger.info("[Dividing] Get all workers get ranges")
              }
              case Failure(exception) => {
                state = FAILED
                logger.info("[Dividing] Dividing is failed: " + exception)
              }
            }
          }
        }
      }
    }

    override def divide(request: DivideRequest): Future[DivideResponse] = state match {
      case MDIVIDE => {
        def convertWorkersTOMessage(): Seq[workerInfo] = {
          (workers map {case (id, worker) => WorkerData.workerDataToMessage(worker)}).toSeq
        }
        logger.info(s"[divide] Send to Worker${request.workerId} and the range: ${convertWorkersTOMessage}")
        Future.successful(new DivideResponse(Stat.SUCCESS, workerNum, convertWorkersTOMessage))
      }
      case FAILED => Future.failed(new Exception("[divide] Fail to dividing"))
      case _ => Future.successful(new DivideResponse(Stat.PROCESSING))
    }

    override def startShuffle(request: StartShuffleRequest): Future[StartShuffleResponse] = {
      assert(workers(request.workerId).state == WSAMPLE || workers(request.workerId).state == WSORT)
      if (checkWorkersState(MDIVIDE, WSORT)) {
        state = MSHUFFLE
      }
      if (workers(request.workerId).state == WSAMPLE) {
        workers.synchronized(workers(request.workerId).state = WSORT)
      }
      state match {
        case MSHUFFLE => {
          logger.info(s"[startShuffle] Start shuffling")
          Future.successful(new StartShuffleResponse(Stat.SUCCESS))
        }
        case _ => Future.successful(new StartShuffleResponse(Stat.PROCESSING))
      }
    }

    override def sort(request: SortRequest): Future[SortResponse] = {
      assert(workers(request.workerId).state == WSORT || workers(request.workerId).state == WSHUFFLE)
      if (checkWorkersState(MSHUFFLE, WSHUFFLE)) {
        state = MSORT
      }
      if (workers(request.workerId).state == WSORT) {
        workers.synchronized(workers(request.workerId).state = WSHUFFLE)
      }
      state match {
        case MSORT => {
          logger.info("[sort] All Workers is sorted")
          Future.successful(new SortResponse(Stat.SUCCESS))
        }
        case _ => Future.successful(new SortResponse(Stat.PROCESSING))
      }
    }

    override def terminate(request: TerminateRequest): Future[TerminateResponse] = {
      workers.synchronized( {
        workers(request.workerId).state = WTERMINATE
        if (checkWorkersState(MSORT, WTERMINATE)) {
          logger.info(s"[terminate] All Workers terminated. Master terminate")
          state = MTERMINATE
          shutdownServer()
        }
      })
      Future.successful(new TerminateResponse)
    }
  }
}
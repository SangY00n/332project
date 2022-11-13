package messagePair

import messagePair.network.ConnectorGrpc.ConnectorBlockingStub
import messagePair.network.{ConnectorGrpc, ConnectRequest}
import io.grpc.{ManagedChannel, ManagedChannelBuilder, StatusRuntimeException}

import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger}

/**
 * [[https://github.com/grpc/grpc-java/blob/v0.15.0/examples/src/main/java/io/grpc/examples/helloworld/HelloWorldClient.java]]
 */

class ConnectionClient (ipAdd:String,port:Int) {

  val logger = Logger.getLogger(classOf[ConnectionClient].getName)
  val channel = ManagedChannelBuilder.forAddress(ipAdd, port).usePlaintext().build
  val blockingStub = ConnectorGrpc.blockingStub(channel)
  def shutdown(): Unit = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }

  /** Send connect request. */
  def connectToServer(ipAdd: String, port:Int): Unit = {
    logger.info("Client request : Ip address is " + ipAdd+ " and port is" +port)
    val request =ConnectRequest(ipAdd, port)
    try {
      val response = blockingStub.connect(request)
      if (response.isSuccess==true)
        {logger.info("Client -server connection is completed")
        }
      else
        {
          logger.info("failed to connection")
        }
    }
    catch {
      case e: StatusRuntimeException =>
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus)
    }
  }
}
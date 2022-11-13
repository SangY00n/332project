package messagePair

import messagePair.network.ConnectorGrpc.ConnectorBlockingStub
import messagePair.network.{ConnectorGrpc, ConnectRequest}
import io.grpc.{ManagedChannel, ManagedChannelBuilder, StatusRuntimeException}

import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger}

/**
 * [[https://github.com/grpc/grpc-java/blob/v0.15.0/examples/src/main/java/io/grpc/examples/helloworld/HelloWorldClient.java]]
 */
object ConnectionClient {
  def apply(host: String, port: Int): ConnectionClient = {
    val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    val blockingStub = ConnectorGrpc.blockingStub(channel)
    new ConnectionClient(channel, blockingStub)
  }

  def main(args: Array[String]): Unit = {
    val client = ConnectionClient()
    try {
        client.connectToServer(i);
    } finally {
      client.shutdown()
    }
  }
}

class ConnectionClient private(
                                private val channel: ManagedChannel,
                                private val blockingStub: ConnectorBlockingStub
                              ) {
  private[this] val logger = Logger.getLogger(classOf[ConnectionClient].getName)

  def shutdown(): Unit = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }

  /** Send connect request. */
  def connectToServer(ipAdd: String, port:Int): Unit = {
    logger.info("Client request : Ip address is " + ipAdd+ " and port is" +port)
    val request =ConnectRequest(ipAdd, port)
    try {
      val response = blockingStub.connect(request)
      logger.info("Greeting: " + response.message)
    }
    catch {
      case e: StatusRuntimeException =>
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus)
    }
  }
}
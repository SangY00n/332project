package messagePair

import scala.concurrent.ExecutionContext
import java.net._
import messagePair.ConnectionServer


object Master
{

  def main(args:Array[String]):Unit={
    val port : Int= 50051
    val server= new ConnectionServer(ExecutionContext.global)

    server.start()
    server.blockUntilShutdown()
  }


}

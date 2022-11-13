package messagePair

import scala.concurrent.ExecutionContext
import java.net._
import messagePair.ConnectionClient


object Worker
{

  def main(args:Array[String]):
  Unit={

    val client= new ConnectionClient("localhost", 50051)

    try {
      client.connectToServer("1",50051)
    }
    finally
      {
        client.shutdown()
      }
  }


}

package network

class ClientMeta (
                   val inputDirPaths: Seq[String],
                   val outputDirPath: String,
                   val masterIP: String,
                   val masterPort: Int,
                   val shuffleServerIP: String
                 ) {}
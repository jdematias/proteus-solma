package eu.proteus.solma.utils

import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar

object FileUtils {

  def writeCutreLog(message: String): Unit = {
    val now = Calendar.getInstance().getTime
    val fw = new FileWriter("/tmp/LassoLog.txt", true)
    val timeFormat = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss")
    val currentTimeAsString = timeFormat.format(now)

    try {
      fw.write(currentTimeAsString + " - " + message + "\n")
    }
    finally fw.close()
  }

}

/*
 * Run Paper Gradle Plugin
 * Copyright (c) 2021 Jason Penilla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.jpenilla.runpaper.util

import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

internal class Downloader(
  val remote: URL,
  val destination: Path
) {
  private var started = false

  @Volatile
  private var downloaded = 0L

  @Volatile
  private var expectedSize = 0L

  fun download(listener: ProgressListener): Result {
    if (this.started) {
      error("Cannot start download a second time.")
    }
    this.started = true

    val downloadThread = Thread {
      val connection = this.remote.openConnection()
      val expected = connection.contentLengthLong
      listener.onStart(expected)
      this.expectedSize = expected
      Channels.newChannel(connection.getInputStream()).use { remote ->
        val wrapped = ReadableByteChannelWrapper(remote) { bytesRead ->
          this.downloaded += bytesRead
        }
        FileOutputStream(this.destination.toFile()).use {
          it.channel.transferFrom(wrapped, 0, Long.MAX_VALUE)
        }
      }
    }
    downloadThread.start()

    val executor = Executors.newSingleThreadScheduledExecutor()
    val emitProgress = {
      if (this.expectedSize != 0L) {
        listener.updateProgress(this.downloaded)
      }
    }
    val task = executor.scheduleAtFixedRate(emitProgress, 0L, listener.updateRateMs, MILLISECONDS)

    var failure: Exception? = null
    try {
      downloadThread.join()
    } catch (ex: InterruptedException) {
      Thread.currentThread().interrupt()
      failure = ex
    } catch (ex: Exception) {
      failure = ex
    } finally {
      task.cancel(true)
      executor.shutdownNow()
    }

    if (failure == null) {
      emitProgress()
      listener.close()
      return Result.Success(this.downloaded)
    }

    listener.close()
    return Result.Failure(failure)
  }

  sealed class Result {
    data class Failure(val throwable: Throwable) : Result()
    data class Success(val bytesDownloaded: Long) : Result()
  }

  interface ProgressListener : AutoCloseable {
    fun onStart(expectedSize: Long)
    fun updateProgress(bytesDownloaded: Long)
    val updateRateMs: Long
  }

  private class ReadableByteChannelWrapper(
    private val wrapped: ReadableByteChannel,
    private val readCallback: (Int) -> Unit
  ) : ReadableByteChannel by wrapped {
    @Throws(IOException::class)
    override fun read(byteBuffer: ByteBuffer): Int {
      val read = this.wrapped.read(byteBuffer)
      if (read > 0) {
        this.readCallback(read)
      }
      return read
    }
  }
}

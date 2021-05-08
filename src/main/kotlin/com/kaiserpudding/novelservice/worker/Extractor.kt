package com.kaiserpudding.novelservice.worker

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.URL

class Extractor {

    private val downloader by lazy { Downloader() }

    suspend fun extractSingle(url: String, file: File) {
        val content = downloader.download(url)
        writeContent(content, file)
    }

    suspend fun writeContent(content: String, file: File) {
        withContext(Dispatchers.IO) {
            val writer = FileWriter(file)
            writer.write("<html><body>")
            writer.write(content)
            writer.write("</body></html>")
            writer.flush()
            writer.close()
        }
    }

    /**
     * Extracts multiple file from the given [link] of a "table of contents" site.
     * The chapters get identified by the [regex].
     *
     * @param link The url to extract the links from.
     * @param folder The place to save the downloaded files.
     * @param regex Used to identify which urls in the html of the given link to download.
     * Default is "chapter \\d+" ("chapter " followed by numbers).
     * @return A [Channel] of [Pair] of [String].
     * [Pair.first] is the url of the download, [Pair.second] is the file name.
     */
    suspend fun extractMulti(
        downloadInfos: List<DownloadInfo>,
        folder: File,
        waitTime: Long = 1000L
    ): Channel<String> {
        val channel = Channel<String>()
        GlobalScope.launch {
            downloadInfos.forEach {
                if (!folder.exists()) folder.mkdir()
                extractSingle(it.url, File(folder.path + File.separator + it.name.replaceInvalidFilenameChar() + ".html"))
                channel.send(it.name)
                delay(waitTime)
            }
            channel.close()
        }
        return channel
    }

    suspend fun extractDownloadInfos(
        link: String,
        regex: Regex = DEFAULT_CHAPTER_REGEX
    ): List<DownloadInfo> {
        return withContext(Dispatchers.IO) {
            val result: MutableList<DownloadInfo> = mutableListOf()

            val url = URL(link)
            val doc = Jsoup.connect(link).userAgent(USER_AGENT).get()

            doc.select(HTML_LINK_TAG).filter {
                val href = it.attr(HTML_HREF_ATTR)
                val isSameSiteUrl = href.contains(url.host, true)
                        || (href.startsWith("/") && !href.startsWith("//"))
                isSameSiteUrl
            }.forEach {
                var chapterUrl = it.attr(HTML_HREF_ATTR)
                val name = it.text()
                val isChapter = it.hasText()
                        && it.text().contains(regex)
                        && chapterUrl != "/"
                if (chapterUrl.startsWith("/")) chapterUrl = url.protocol + "://" + url.host + chapterUrl
                result.add(
                    DownloadInfo(
                        isChapter,
                        name,
                        chapterUrl
                    )
                )
            }
            return@withContext result
        }
    }

    suspend fun convert(filename: String, toFormat: String, folder: File) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("cmd.exe", "/c", "ebook-convert $filename.html $filename.$toFormat")
            .directory(folder)
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        reader.forEachLine { println(it) }
        reader.close()
    }

    companion object {
        private val DEFAULT_CHAPTER_REGEX =
            "([cC]hapter |[cC]h\\.?[ ]?)\\d+|(\\d+[ ]?[.:\\-])".toRegex()
        private const val HTML_LINK_TAG = "a"
        private const val HTML_HREF_ATTR = "href"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:71.0) Gecko/20100101 Firefox/71.0"
    }
}

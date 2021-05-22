package com.kaiserpudding.novelservice.worker

import com.kaiserpudding.novelservice.api.jms.NovelConfigMessage
import com.kaiserpudding.novelservice.api.service.FileService
import com.kaiserpudding.novelservice.db.NovelRepository
import com.kaiserpudding.novelservice.infratructure.AmqConfigurator
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component
import java.lang.Exception
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.setPosixFilePermissions

@ExperimentalPathApi
@Component
class NovelMessageListener(
    @Autowired private val novelRepository: NovelRepository,
    @Autowired private val fileService: FileService
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(NovelMessageListener::class.java)
    }

    private val extractor = Extractor()

    @JmsListener(destination = AmqConfigurator.NOVEL_QUEUE)
    fun processNovel(config: NovelConfigMessage) {
        LOG.info("Received novel job with config: '$config'")

        try {

            runBlocking {
                if (config.tableOfContents) {
                    LOG.info("Multi download not implemented yet")
                } else {
                    LOG.info("Single download starting")
                    val tmpDir = Files.createTempDirectory("novel")
                    val tmpFile = Files.createTempFile(tmpDir, "novel", ".html")
                    val permissions = PosixFilePermission.values().toSet()
                    tmpDir.setPosixFilePermissions(permissions)
                    tmpFile.setPosixFilePermissions(permissions)

                    extractor.extractSingle(config.url, tmpFile.toFile())
                    val resultFile = extractor.convert(tmpFile.nameWithoutExtension, "epub", tmpDir.toFile())

                    val fileId = fileService.saveNovel(resultFile)

                    LOG.info("Updating novel with id '${config.novelId}' with file id '$fileId'")
                    val novel = novelRepository.findById(UUID.fromString(config.novelId))
                    novel.ifPresentOrElse(
                        {
                            it.fileId = fileId
                            novelRepository.save(it)
                        },
                        { LOG.error("Novel with id '${config.novelId}' was not found") }
                    )
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed novel job with config: '$config'")
        }

    }

}
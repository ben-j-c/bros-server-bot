package bsb

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.reflect.KClass

data class Config(val discord: Discord)

data class Discord(val token: String)

private val mapper: ObjectMapper
    get() {
        val mapper = ObjectMapper(YAMLFactory())
        mapper.registerModule(KotlinModule())
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        return mapper
    }

fun parse(filename: String): Config {
    return Files.newBufferedReader(FileSystems.getDefault().getPath(filename)).use {
        mapper.readValue(it, Config::class.java)
    }
}
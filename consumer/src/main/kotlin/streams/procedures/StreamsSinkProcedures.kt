package streams.procedures

import org.apache.commons.lang3.exception.ExceptionUtils
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.kernel.internal.GraphDatabaseAPI
import org.neo4j.logging.Log
import org.neo4j.procedure.Context
import org.neo4j.procedure.Description
import org.neo4j.procedure.Mode
import org.neo4j.procedure.Name
import org.neo4j.procedure.Procedure
import streams.StreamsEventConsumer
import streams.StreamsEventConsumerFactory
import streams.StreamsEventSink
import streams.StreamsEventSinkConfigMapper
import streams.StreamsSinkConfiguration
import streams.events.StreamsPluginStatus
import streams.utils.Neo4jUtils
import java.util.stream.Collectors
import java.util.stream.Stream

class StreamResult(@JvmField val event: Map<String, *>)
class KeyValueResult(@JvmField val name: String, @JvmField val value: Any?)

class StreamsSinkProcedures {

    @JvmField @Context
    var log: Log? = null

    @JvmField @Context
    var db: GraphDatabaseService? = null

    @Procedure(mode = Mode.READ, name = "streams.consume")
    @Description("streams.consume(topic, {timeout: <long value>, from: <string>, groupId: <string>, commit: <boolean>, partitions:[{partition: <number>, offset: <number>}]}) " +
            "YIELD event - Allows to consume custom topics")
    fun consume(@Name("topic") topic: String?,
                @Name(value = "config", defaultValue = "{}") config: Map<String, Any>?): Stream<StreamResult> {
        checkEnabled()
        if (topic.isNullOrEmpty()) {
            log?.info("Topic empty, no message sent")
            return Stream.empty()
        }

        val properties = config?.mapValues { it.value.toString() } ?: emptyMap()
        val configuration = streamsEventSinkConfigMapper.convert(config = properties)

        val data = readData(topic, config ?: emptyMap(), configuration)

        return data.map { StreamResult(mapOf("data" to it)) }.stream()
    }

    @Procedure("streams.sink.start")
    fun sinkStart(): Stream<KeyValueResult> {
        checkEnabled()
        checkLeader()
        try {
            streamsEventSink?.start()
            return sinkStatus()
        } catch (e: Exception) {
            log?.error("Cannot start the Sink because of the following exception", e)
            return Stream.concat(sinkStatus(),
                    Stream.of(KeyValueResult("exception", ExceptionUtils.getStackTrace(e))))
        }
    }

    @Procedure("streams.sink.stop")
    fun sinkStop(): Stream<KeyValueResult> {
        checkEnabled()
        checkLeader()
        try {
            streamsEventSink?.stop()
            return sinkStatus()
        } catch (e: Exception) {
            log?.error("Cannot stopped the Sink because of the following exception", e)
            return Stream.concat(sinkStatus(),
                    Stream.of(KeyValueResult("exception", ExceptionUtils.getStackTrace(e))))
        }
    }

    @Procedure("streams.sink.restart")
    fun sinkRestart(): Stream<KeyValueResult> {
        val stopped = sinkStop().collect(Collectors.toList())
        val hasError = stopped.stream().anyMatch { it.name == "exception" }
        if (hasError) {
            return stopped.stream()
        }
        return sinkStart()
    }

    @Procedure("streams.sink.config")
    fun sinkConfig(): Stream<KeyValueResult> {
        checkEnabled()
        checkLeader()
        return streamsSinkConfiguration.asMap()
                .entries
                .stream()
                .map { KeyValueResult(it.key, it.value) }
    }

    @Procedure("streams.sink.status")
    fun sinkStatus(): Stream<KeyValueResult> {
        checkEnabled()
        checkLeader()
        val value = (streamsEventSink?.status() ?: StreamsPluginStatus.UNKNOWN).toString()
        return Stream.of(KeyValueResult("status", value))
    }

    private fun checkLeader() {
        if (!Neo4jUtils.isWriteableInstance(db as GraphDatabaseAPI)) {
            throw RuntimeException("You can use it only in the LEADER or in a single instance configuration.")
        }
    }

    private fun readData(topic: String, procedureConfig: Map<String, Any>, consumerConfig: Map<String, String>): List<Any> {
        val cfg = procedureConfig.mapValues { if (it.key != "partitions") it.value else mapOf(topic to it.value) }
        val timeout = cfg.getOrDefault("timeout", 1000).toString().toLong()
        val consumer = createConsumer(consumerConfig, topic)
        consumer.start()
        val data = mutableListOf<Any>()
        try {
            val start = System.currentTimeMillis()
            while ((System.currentTimeMillis() - start) < timeout) {
                consumer.read(cfg) { _, topicData ->
                    data.addAll(topicData.mapNotNull { it.value })
                }
            }
        } catch (e: Exception) {
            if (log?.isDebugEnabled!!) {
                log?.error("Error while consuming data", e)
            }
        }
        if (log?.isDebugEnabled!!) {
            log?.debug("Data retrieved from topic $topic after $timeout milliseconds: $data")
        }
        consumer.stop()
        return data
    }

    private fun createConsumer(consumerConfig: Map<String, String>, topic: String): StreamsEventConsumer {
        return streamsEventConsumerFactory
                .createStreamsEventConsumer(consumerConfig, log!!)
                .withTopics(setOf(topic))
    }

    private fun checkEnabled() {
        if (!streamsSinkConfiguration.proceduresEnabled) {
            throw RuntimeException("In order to use the procedure you must set streams.procedures.enabled=true")
        }
    }


    companion object {
        private lateinit var streamsSinkConfiguration: StreamsSinkConfiguration
        private lateinit var streamsEventConsumerFactory: StreamsEventConsumerFactory
        private lateinit var streamsEventSinkConfigMapper: StreamsEventSinkConfigMapper
        private var streamsEventSink: StreamsEventSink? = null

        fun registerStreamsEventSinkConfigMapper(streamsEventSinkConfigMapper: StreamsEventSinkConfigMapper) {
            this.streamsEventSinkConfigMapper = streamsEventSinkConfigMapper
        }

        fun registerStreamsSinkConfiguration(streamsSinkConfiguration: StreamsSinkConfiguration) {
            this.streamsSinkConfiguration = streamsSinkConfiguration
        }

        fun registerStreamsEventConsumerFactory(streamsEventConsumerFactory: StreamsEventConsumerFactory) {
            this.streamsEventConsumerFactory = streamsEventConsumerFactory
        }

        fun registerStreamsEventSink(streamsEventSink: StreamsEventSink) {
            this.streamsEventSink = streamsEventSink
        }
    }
}
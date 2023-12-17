import kotlinx.coroutines.*
import java.io.*
import kotlin.properties.Delegates

/**
 * If [redirectErrorStream] is true then you should only care about the
 * [parseStdoutLine] method.
 */
abstract class Executable<T>(
    private var path: String,
    private val stopAfterMilli: Long = Long.MAX_VALUE,
    private val defaultStdinString: String = "",
    private val redirectErrorStream: Boolean = true){

    class TimeoutExecutableException: Exception()

    private lateinit var stdout: BufferedReader
    private lateinit var stderr: BufferedReader
    private lateinit var stdin: BufferedWriter

    companion object {
        const val AR_TIME_LIMIT = "TIME_LIMIT"
    }
    
    var abortReason: String? = null
    abstract var construction: T?
    protected val args: MutableList<String> = mutableListOf()

    private lateinit var process: Process
    private var start by Delegates.notNull<Long>()

    open suspend fun execute(scope: CoroutineScope): Deferred<T?> =  scope.async {
        val processBuilder = ProcessBuilder()
        processBuilder.command(path, *args.toTypedArray())
        processBuilder.redirectErrorStream(redirectErrorStream)

        process = processBuilder.start()

        stdout = BufferedReader(InputStreamReader(process.inputStream))
        stderr = BufferedReader(InputStreamReader(process.errorStream))
        stdin = BufferedWriter(OutputStreamWriter(process.outputStream))

        if(defaultStdinString != ""){
            stdin.write(defaultStdinString)
            stdin.flush()
        }

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        start = System.currentTimeMillis()
        var keepTrackOfTimeJob: Job? = null

        val timeoutCallback: suspend () -> T? = callback@{
            process.destroy()
            abortReason = AR_TIME_LIMIT
            parseRemaining(stdout, stdoutBuilder)
            parseRemaining(stderr, stderrBuilder)
            onTimeout()
            keepTrackOfTimeJob?.cancelAndJoin()
            return@callback construction
        }

        try {

            var hasTimeout = false

            if (stopAfterMilli != Long.MAX_VALUE) {

                keepTrackOfTimeJob = launch {

                    val start = System.currentTimeMillis()

                    while(System.currentTimeMillis() - start < stopAfterMilli){
                        delay(100)
                        if(abortReason != null){
                            break
                        }
                    }

                    if(abortReason == null)
                        abortReason = AR_TIME_LIMIT

                    if (process.isAlive){
                        process.destroy()
                        hasTimeout = true
                    }

                }
            }

            val stdoutJob = startReadingBufferedReader(stdoutBuilder, this, stdout)
            var stderrJob: Job? = null
            if (!redirectErrorStream) {
                stderrJob = startReadingBufferedReader(stderrBuilder, this, stderr)
            }
            stdoutJob.join()
            stderrJob?.join()

            if(hasTimeout)
                throw TimeoutExecutableException()

        } catch (_: TimeoutExecutableException){
            timeoutCallback()
            return@async construction
        } catch (e: TimeoutCancellationException){
            timeoutCallback()
            return@async construction
        }

        keepTrackOfTimeJob?.cancelAndJoin()
        parseRemaining(stdout, stdoutBuilder)
        parseRemaining(stderr, stderrBuilder)
        process.waitFor()
        onFinish()
        return@async construction
    }

    private suspend fun parseRemaining(bufferedReader: BufferedReader, builder: java.lang.StringBuilder){
        val finalLine = builder.toString()
        builder.clear()

        if (finalLine.isNotEmpty() && isParseable(finalLine, (-1).toChar())) {
            parseLine(finalLine, (-1).toChar(), bufferedReader, stdin)
        }
    }

    private suspend fun startReadingBufferedReader(stringBuilder: StringBuilder,
                                                   scope: CoroutineScope,
                                                   bufferedReader: BufferedReader) = scope.launch {
        var character: Int
        var line = ""

        try {
            while(!bufferedReader.ready() && process.isAlive)
                yield()
            character = bufferedReader.read()
            ensureActive()
        } catch (e: IOException) {
            return@launch
        }

        //It's better to avoid using readline() as we lose
        //the terminator character, here we ensure to get it.
        while (character != -1) {
            val char = character.toChar()
            if (isParseable(line, char)) {
                line = stringBuilder.toString()
                parseLine(line, char, bufferedReader, stdin)
                stringBuilder.clear()
            } else {
                stringBuilder.append(char)
            }
            try {
                while(!bufferedReader.ready() && process.isAlive)
                    yield()
                character = bufferedReader.read()
            } catch (e: IOException) {
                return@launch
            }
        }

        ensureActive()
        val finalLine = stringBuilder.toString()
        stringBuilder.clear()

        if (isParseable(finalLine, (-1).toChar())) {
            parseLine(finalLine, (-1).toChar(), bufferedReader, stdin)
        }
    }

    /**
     * This function is called if the execution was timed out.
     * [onFinish] and [onTimeout] cannot be called together. If one is called,
     * then the other will never be.
     */
    open fun onTimeout(){}

    /**
     * Parse line from the stdout, defined by [isParseable].
     */
    abstract suspend fun parseStdoutLine(line: String, sep: Char, stdout: BufferedReader, stderr: BufferedReader, stdin: BufferedWriter)

    /**
     * Parse line from the stderr, defined by [isParseable].
     */
    open suspend fun parseStderrLine(line: String, sep: Char, stdout: BufferedReader, stderr: BufferedReader, stdin: BufferedWriter){}

    /**
     * Parse the given line received from the output stream of the executable process.
     */
    private suspend fun parseLine(line: String, sep: Char, usedBufferedReader: BufferedReader, stdin: BufferedWriter){
        if(usedBufferedReader === stdout)
            parseStdoutLine(line, sep, stdout, stderr, stdin)
        else
            parseStderrLine(line, sep, stdout, stderr, stdin)
    }

    /**
     * This method is called when the executable procedure is finished.
     * It is not invoked when an exception occurred.
     */
    open fun onFinish(){}

    /**
     * Return weather [line] should be parsed or not.
     **/
    open fun isParseable(line: String, current: Char): Boolean{
        return current == '\n' || current == '\r' || current == (-1).toChar()
    }
}

/**
 * The purpose of this function is to launch fast ending program, as the timeout
 * of this is 1sec.
 *
 * If you wish to launch big command use [Executable].
 *
 * This function actually only reads the merged stdout/stderr and returns it.
 */
suspend fun executeCommand(scope: CoroutineScope, command: List<String>,
                           stopAfterMilli: Long = 1000): String {
    val executable = object : Executable<StringBuilder>(command.first(), stopAfterMilli, redirectErrorStream = true) {
        override var construction: StringBuilder? = java.lang.StringBuilder()
        override suspend fun parseStdoutLine(
            line: String,
            sep: Char,
            stdout: BufferedReader,
            stderr: BufferedReader,
            stdin: BufferedWriter
        ) {
            construction?.append(line)

            // if !EOF
            if(sep != (-1).toChar())
                construction?.append(sep)
        }

        init {
            args.addAll(command.drop(1))
        }
    }

    return executable.execute(scope).await()?.toString() ?: ""
}
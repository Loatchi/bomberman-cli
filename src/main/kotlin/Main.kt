import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.colormath.model.RGB
import com.github.ajalt.mordant.animation.progressAnimation
import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.table.RowHolderBuilder
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jline.terminal.TerminalBuilder
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.measureTime

class BombermanCommonOptions: OptionGroup("Default"){

    val playerC: Path by option("--player-c")
        .path(mustBeReadable = true, mustExist = true)
        .default(Path("player.c"))
        .help { "The player.c file" }
        .validate {
            if(!it.exists() || !it.isRegularFile())
                fail("path \"${it.absolutePathString()}\" does not exist.")
        }

    val tmpDir: Path by option("--tmp-dir")
        .path()
        .defaultLazy { Files.createTempDirectory("bomberman") }
        .help { "The temporary directory to use. If it does not exist, it will be created." }
        .validate {
            if(it.exists()){

                if(!it.isDirectory())
                    fail("The given path does not represent a directory.")

                if(it.listDirectoryEntries().isNotEmpty())
                    fail("The temporary directory must be empty as it will be deleted.")

            } else {
                it.createDirectory()
            }
        }

    val compilerArgs: List<String> by option("--gcc-args")
        .varargValues()
        .default(listOf("-lm"))
        .help { "The arguments to add to gcc." }
}

class BombermanCLI: NoOpCliktCommand(allowMultipleSubcommands = true){
    init {
        completionOption()
    }
}

class BombermanCompile: CliktCommand(name = "compile", help =
        """
        Compile your player.c into a bomberman executable and nothing else.
        """.trimIndent()){

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val output: Path by argument().path().help {
        "The path to the final executable file."
    }
    private val commonOptions by BombermanCommonOptions()

    override fun run() {
        val bombermanExec = output.toFile()
        val playerCFile = commonOptions.playerC.toFile()
        val proj = Project(playerCFile, bombermanExec, commonOptions.tmpDir.toFile(), commonOptions.compilerArgs,
            isForBenchmark = false)

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            proj.deleteEnv()
        })

        proj.use {
            proj.compile()
        }
    }
}

class BombermanGame: CliktCommand(name = "play", help =
        """
        To properly use <play> you must respect a contract about player.c. The parser uses the stdout
        of a freshly compiled bomberman executable. You should not interfere with this process. Instead
        write everything to STDERR (achieved with `fprintf(stderr, <format>, ...)` in C). The program
        will read the STDERR and automatically pretty print STDERR, miscellaneous data and the map.
        If you did not understand, use --stdout-as-stderr, it will change every printf to fprintf(stderr,...).
        *Obviously it will not modify the given player.c, but apply this modification in a copy of player.c.*
        You can interact with the game being played using <play>'s STDIN: 

        | Char     | Action                                |
        | -------- | ------------------------------------- |
        | 'v'      | Add 100 to the delay (up to 5000)     |
        | 'b'      | Reduce the delay by 100 (down to 100) |
        | 'c'      | Go to previous round                  |
        | 'n'      | Go to next round                      |
        | '<space>'| Pause the game                        |
        | 'x'      | Exit the game if the game is paused   |
        
        After ending a game you'll see a BombermanGameResult object.

        """.trimIndent()){

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val delay: Int by option("--delay", "-d")
        .int()
        .default(500)
        .help { "The delay (ms) between each redraw of the map." }
    private val level: Int by option("--level", "-l")
        .int(true)
        .restrictTo(0..2)
        .required()
        .help { "The level to play in." }
    private val seed: Long by option("--seed", "-s")
        .long()
        .defaultLazy { System.nanoTime() }
        .help { "The random generator seed used for the game." }

    private val changeStdoutToStderr: Boolean by option("--stdout-as-stderr")
        .flag(default = false)
        .help { "Use this option if you want to automatically change every `printf` in your player.c into `fprintf(stderr, ...)` calls." }

    private val commonOptions by BombermanCommonOptions()

    private val title = """
            ______                 _                                     
            | ___ \               | |                                    
            | |_/ / ___  _ __ ___ | |__   ___ _ __ _ __ ___   __ _ _ __  
            | ___ \/ _ \| '_ ` _ \| '_ \ / _ \ '__| '_ ` _ \ / _` | '_ \ 
            | |_/ / (_) | | | | | | |_) |  __/ |  | | | | | | (_| | | | |
            \____/ \___/|_| |_| |_|_.__/ \___|_|  |_| |_| |_|\__,_|_| |_|
             _____                  _                                    
            /  ___|                (_)                                   
            \ `--. _   _ _ ____   _____   _____  _ __                    
             `--. \ | | | '__\ \ / / \ \ / / _ \| '__|                   
            /\__/ / |_| | |   \ V /| |\ V / (_) | |                      
            \____/ \__,_|_|    \_/ |_| \_/ \___/|_|                      
        """.trimIndent()
    private val FRAME_LENGTH = 30L

    override fun run() {
        val playerCFile = commonOptions.playerC.toFile()
        val proj = Project(playerCFile,
            null,
            commonOptions.tmpDir.toFile(),
            commonOptions.compilerArgs,
            changeStdoutToStderr,
            true)
        val bombermanExec: File
        val bomberman: Bomberman
        val gameResult: BombermanGameResult
        val maxTimeout = 5000L

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            proj.deleteEnv()
        })

        proj.use {
            bombermanExec = proj.compile()
            bomberman = Bomberman(bombermanExec, proj.tmpDir, 0, DisplayType.COLOR,
                level, seed=seed, saveLineWithAnsi = true, fullGameInsightMode = true,
                timeoutAfter = maxTimeout)
            gameResult = runBlocking {
                bomberman.execute(this).await()!!
            }
        }

        clearTerminal()
        cursorToY(0)

        runBlocking {

            var pause = false
            var round = 1
            var maxRound = Int.MAX_VALUE
            var usedDelay = delay
            var stopPlaying = false
            val term = TerminalBuilder.terminal()

            CoroutineScope(Dispatchers.IO).launch {
                term.enterRawMode()
                var c = term.reader().read()
                while(c != -1){
                    when(c){
                        'v'.code -> if(usedDelay >= 200) usedDelay -= 100
                        'b'.code -> if(usedDelay < 5000) usedDelay += 100
                        'c'.code -> if(pause && round > 1) round--
                        'n'.code -> if(pause && round < maxRound) round++
                        ' '.code -> pause = !pause
                        'x'.code -> if(pause) stopPlaying = true
                    }
                    c = term.reader().read()
                }
            }

            printTitle()
            println()
            printCredits()
            println()

            if(gameResult.result == GameResult.UNKNOWN){
                System.err.println("The created game is invalid.")
                System.err.println("See the output BombermanGameResult object:")
                System.err.println(gameResult)
                return@runBlocking
            }

            maxRound = gameResult.history!!.size
            round = bomberman.totalGameHistory[0].rounds
            var map: List<String>
            var redraws = 0
            var firstDrawHappen = false

            do {
                map = gameResult.history[round - 1]
                if(firstDrawHappen)
                    cursorToY(title.count { it == '\n'} + 1 + 1 + 2)
                else
                    firstDrawHappen = true
                map.forEachIndexed mapDraw@{j, line ->

                    val partialResult = bomberman.totalGameHistory[round - 1]

                    if(partialResult.brokenWall == -1){
                        print(line)
                        return@mapDraw
                    }

                    when (j) {
                        2 -> {
                            printStat(line, "Delay", usedDelay, "<v>=-100, <b>=+100")
                        }
                        3 -> {
                            printStat(line, "Pause", pause, "press <space> to pause" +
                                    if(pause) ", press <x> to stop the game" else ""
                            )
                        }
                        4 -> {
                            printStat(line, "RemainingBombs", partialResult.remainingBombs, "")
                        }
                        5 -> {
                            printStat(line, "Score", partialResult.score, "")
                        }
                        6 -> {
                            printStat(line, "Round", partialResult.rounds, "max=$maxRound, <c>=round--, <n>=round++")
                        }
                        7 -> {
                            val value = if(partialResult.actions != null) partialResult.actions.first() else ""
                            printStat(line, "NextAction", value, "")
                        }
                        8 -> {
                            printStat(line, "BrokenWall", partialResult.brokenWall, partialResult.signalTrace ?: "")
                        }
                        9 -> {
                            printStat(line, "EnemyKilled", "(flame=${partialResult.flameEnemyKilled}," +
                                    "ghost=${partialResult.ghostEnemyKilled})", "")
                        }
                        10 -> {
                            printStat(line, "EnemyKilled", "(bomb=${partialResult.bombBonusTaken}," +
                                    "flame=${partialResult.flameBonusTaken})", "")
                        }
                        11 -> {
                            printStat(line, "Result", gameResult.result, if(gameResult.result == GameResult.ERROR)
                                gameResult.signal.toString() + " at " + gameResult.signalTrace.toString() else "")
                        }
                        else -> print(line)
                    }
                }

                cursorToY(28)
                print("Bomberman's STDERR at round=$round:")
                eraseUntilEndOfLine()
                println()
                print(bomberman.totalStderrHistory[round - 1]
                    .replace("\n", "\u001B[0K\n")
                        + "\u001B[0K")

                if(redraws * FRAME_LENGTH >= usedDelay && !pause){
                    redraws = 0
                    round++
                }

                delay(FRAME_LENGTH)
                redraws++

                if(round == maxRound)
                    pause = true

            } while ((pause || round <= maxRound) && !stopPlaying)
        }
        println(gameResult.light())
    }

    private fun printStat(line: String, statName: String, value: Any, message: String){
        print(line.substringBeforeLast('\n') + "\t\t$statName: $value${if(message != "") " ($message)" else ""}")
        eraseUntilEndOfLine()
        println()
    }

    private fun eraseUntilEndOfLine(){
        print("\u001B[0K")
    }

    private fun clearTerminal(){
        print("\u001B[2J")
    }

    private fun cursorToY(y: Int){
        print("\u001B[$y;1H")
    }

    private fun printTitle(){
        print(title)
    }

    private fun printCredits(){
        print("""                  
            by Pr Francois Goasdoue, Shaman team, ENSSAT-IRISA, Univ Rennes
            bundled into bomberman_cli by T. Francois
        """.trimIndent())
    }

}

class BombermanBenchmark: CliktCommand(name = "benchmark", help = """
    This subcommand will simulate a batch of Bomberman games and give you the result after a while.
    It is not interactive, you'll be prompted to wait by a progress bar.
    
    Example:
    `<bomberman_cli> benchmark --json data.json -g1000 --levels 0 1 2 --player-c player_model2.c`
    This command will do a total of 3000 games (1000 for each level selected [0, 1, 2]) using the provided source file. 
    It will save the benchmark into *data.json*.
    
    Some data might have some errors in them. Indeed, by how the enemies are being tracked an overlapping might
    occur between a ghost enemy and a flame enemy. In this situation, for a round, the flame enemy will
    be considered dead. It is fine if the game **does not end** on such situation. 
    
    Some other miscellaneous effects might happen. If you plan to use <benchmark> to test if a player.c is good enough
    you should always **compare it to another benchmark made with this application** with -g very large, *so you can
    dilute the uncertainties*.
    
    """.trimIndent()) {

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val timeoutAfter: Int by option("--timeout", "-t").int().restrictTo(1 .. 10000).default(2000)
        .help { "The time in ms before a game is considered a timeout. Values below 300 will almost always cause GameResult.TIMEOUT." }

    private val games: UInt by option("--games", "-g")
        .uint()
        .default(100u)
        .help { "The number of games for the benchmark (for each level), the higher the more accurate the values will be." }

    private val levels: List<Int> by option("--levels", "-l")
        .int()
        .restrictTo(0..2)
        .varargValues(min=1, max=3).required()
        .validate {
            if(it.any { level -> it.count { level2 -> level == level2 } > 1})
                fail("No duplicate is allowed.")
        }

    private val heavy: Boolean by option().flag(default = false).help {
        "If this is activated then each BombermanGameResult object will have both the `actions` and `history` list instead of null. " +
        "You can use it if you want to store everything about the games being played. **However the final BombermanBatchResult " +
        "is not streamed into a json/terminal**; therefore you might run out of memory."
    }

    private val saveAllGames: Boolean by option("--save-all-games").flag(default = false).help {
        "The final BombermanBatchResult will contain the games played. Each game is considered a BombermanGameResult, see " +
                "--heavy for details."
    }

    private val json: Path? by option()
        .path()
        .required()
        .help { "The path to a json file to store the benchmark. It is structured as follow:" +
                " `{ int : BombermanBatchResult }` where `int` is a level."
        }

    private val ignoreProblematicSeed: Boolean by option("--ignore-problematic-seed", "-p")
        .flag(default = false)
        .help { "When running a batch of games it can happen that some game timeouts or segfaults. By default <benchmark> will " +
                "print the seed that were problematic. It is useful if you use --save-all-games and parse the json elsewhere" }

    private val commonOptions by BombermanCommonOptions()
    private val problematicSeeds = mutableListOf<Long>()

    private val batchSize = Runtime.getRuntime().availableProcessors()

    override fun run() {
        runBlocking {
            CoroutineScope(Dispatchers.Default).launch {
                val playerCFile = commonOptions.playerC.toFile()
                val jsonFile = json?.toFile()
                val project = Project(playerCFile, null, commonOptions.tmpDir.toFile(),
                    commonOptions.compilerArgs, isForBenchmark = true)
                val bombermanGameResults = mutableMapOf<Int, MutableList<BombermanGameResult>>()
                val batchResults = mutableMapOf<Int, BombermanBatchResult>()

                Runtime.getRuntime().addShutdownHook(thread(start = false) {
                    project.deleteEnv()
                })

                val time = measureTime {

                    val terminal = Terminal()

                    val progress = terminal.progressAnimation{
                        this.padding = 2
                        text("Playing ${levels.size * games.toLong()} games")
                        percentage()
                        progressBar(showPulse = false)
                        completed()
                        speed("game/s")
                        timeRemaining()
                    }

                    progress.updateTotal(levels.size * games.toLong())

                    project.use {
                        val bombermanExec = project.compile()
                        for (level in levels) {
                            val results = mutableListOf<BombermanGameResult>()
                            var i = games.toInt()
                            while(i >= 0){
                                val numberOfGamesPlayed = min(i, batchSize)
                                playGames(this, bombermanExec, project, level, numberOfGamesPlayed, bombermanGameResults, results)
                                progress.advance(numberOfGamesPlayed.toLong())
                                i -= batchSize
                            }
                            val batchResult = results.toBombermanBatchResult(withGames = if(saveAllGames) bombermanGameResults[level]!! else null)
                            batchResults[level] = batchResult
                        }
                        progress.stop()
                    }
                }

                if(!ignoreProblematicSeed)
                    println(problematicSeeds.joinToString(prefix = "problematicSeeds="))
                jsonFile?.writeText(Json.encodeToString(batchResults))
                println("Computed: $games games (per level) in $time.")
                if(jsonFile == null)
                    batchResults.prettyPrint()
                else
                    println("Data has been written to '$json'.")
            }.join()
        }
    }

    private suspend fun playGames(scope: CoroutineScope,
                                  bombermanExec: File,
                                  project: Project,
                                  level: Int,
                                  n: Int,
                                  bombermanGameResults: MutableMap<Int, MutableList<BombermanGameResult>>,
                                  results: MutableList<BombermanGameResult>){
        val jobs = mutableListOf<Deferred<BombermanGameResult?>>()
        for (k in 0..<n) {
            val bomberman = Bomberman(
                bombermanExec, project.tmpDir, 0,
                DisplayType.BLACK_AND_WHITE, level,
                timeoutAfter.toLong(),
            )
            jobs.add(bomberman.execute(scope))
        }
        val gameResults = jobs.awaitAll().filterNotNull()
        gameResults.forEach {
            if(it.result == GameResult.ERROR || it.result == GameResult.TIMEOUT){
                problematicSeeds.add(it.seed)
            }
        }

        bombermanGameResults
            .getOrPut(level) { mutableListOf() }
            .addAll(if(!heavy) gameResults.map { it.light() } else gameResults)
        results.addAll(gameResults)
    }
}

class BombermanCompare: CliktCommand(name = "compare", help = """
    This command compare the performance of 2 models of player.c
    """.trimIndent()) {

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val jsons: List<Path> by argument()
        .path()
        .multiple()
        .help { "You can send up to 2 jsons. It will compare them. If only one json is provided it will compare it to the 'possible move strategy' *PMS*."}
        .validate {
            if(it.size > 2)
                fail("You can send up to 2 json files.")
            if(it.isEmpty())
                fail("You must provide an argument")
        }

    private val epsilon: Double by option()
        .double()
        .default(0.001)
        .help {
            "The value to define the equality among two floating point numbers."
        }

    override fun run() {

        val bombermanBatchResults = Json.decodeFromString<Map<Int, BombermanBatchResult>>(jsons.first().readText())
        val toCompareToBatchResults = if(jsons.size == 1) Json.decodeFromString<Map<Int, BombermanBatchResult>>(
            InputStreamReader(Project::class.java.getResourceAsStream("/pms.json")!!).readText()
        ) else Json.decodeFromString<Map<Int, BombermanBatchResult>>(jsons[1].readText())

        val bombermanName = jsons.first().fileName
        val toCompareToName = if(jsons.size == 1) "PMS" else jsons[1].fileName

        for (level in maxOf(bombermanBatchResults.keys, toCompareToBatchResults.keys) { it1, it2 -> it1.size.compareTo(it2.size)}) {
            val batchResult = bombermanBatchResults[level]
            val pmsResult = toCompareToBatchResults[level]

            if(batchResult == null){
                println("Missing level=$level in $bombermanName")
                continue
            }

            if(pmsResult == null){
                println("Missing level=$level in $toCompareToName")
                continue
            }

            Terminal().println(table {
                header { row("Level=$level", toCompareToName, bombermanName, "Gain=($bombermanName-$toCompareToName)") }
                body {
                    dataRow("Score", pmsResult.meanScore, batchResult.meanScore, epsilon)
                    dataRow("Broken Wall", pmsResult.meanBrokenWall, batchResult.meanBrokenWall, epsilon)
                    dataRow("Flame Enemy Killed", pmsResult.meanFlameEnemyKilled, batchResult.meanFlameEnemyKilled, epsilon)
                    dataRow("Ghost Enemy Killed", pmsResult.meanGhostEnemyKilled, batchResult.meanGhostEnemyKilled, epsilon)
                    dataRow("Bomb Bonus Taken", pmsResult.meanBombBonusTaken, batchResult.meanBombBonusTaken, epsilon)
                    dataRow("Flame Bonus Taken", pmsResult.meanFlameBonusTaken, batchResult.meanFlameBonusTaken, epsilon)
                    dataRow("Win Rate", pmsResult.winRate, batchResult.winRate, epsilon)
                    dataRow("Rounds", pmsResult.meanRounds, batchResult.meanRounds, epsilon, positive = false)
                    dataRow("Actions", pmsResult.meanActions, batchResult.meanActions)
                    dataRow("Sample Size Difference", pmsResult.sampleSize.toFloat(), batchResult.sampleSize.toFloat(), epsilon,  positive = null)
                }
            })
        }
    }

}

private fun RowHolderBuilder.dataRow(name: String, pmsValue: Float, jsonValue: Float, epsilon: Double, positive: Boolean? = true) {

    val diff = jsonValue - pmsValue

    this.row {
        this.overflowWrap = OverflowWrap.BREAK_WORD
        this.whitespace = Whitespace.PRE_WRAP
        cell(name)
        cell(pmsValue)
        cell(jsonValue)
        cell(if(diff > 0) "+$diff" else diff) {

            val isZero = abs(diff) < epsilon
            if(isZero && positive != null){
                this.style = TextStyle(color = RGB("#555555"))
                return@cell
            }

            when(positive){
                true -> this.style = TextStyle(color = RGB(if(diff < 0) "#ff0000" else "#00ff00"))
                false -> this.style = TextStyle(color = RGB(if(diff > 0) "#ff0000" else "#00ff00"))
                else -> this.style = TextStyle(color = RGB(if(diff.toInt() != 0) "#ff0000" else "#00ff00"))
            }
        }
    }
}

private fun RowHolderBuilder.dataRow(name: String, pmsValue: Map<Action, Float>, jsonValue: Map<Action, Float>) {
    this.row {
        this.overflowWrap = OverflowWrap.BREAK_WORD
        this.whitespace = Whitespace.PRE_WRAP
        cell(name)
        cell(pmsValue)
        cell(jsonValue)
        cell(jsonValue.map { (k, v) -> k to (v - pmsValue[k]!!) }.toMap())
    }
}

private fun Map<Int, BombermanBatchResult>.prettyPrint() {
    values.forEach {
        it.prettyPrint()
    }
}

fun main(args: Array<String>) {
    BombermanCLI().subcommands(BombermanCompile(), BombermanGame(), BombermanBenchmark(), BombermanCompare()).main(args)
}

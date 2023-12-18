import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.system.exitProcess
import kotlin.text.StringBuilder


interface CLIArgument{
    fun asCLIArg(): String
}

enum class DisplayType: CLIArgument {
    BLACK_AND_WHITE {
        override fun asCLIArg(): String = "bw"
    },
    COLOR {
        override fun asCLIArg(): String = "color"
    };
}

private val STDERR_NEXT_ROUND_INDICATOR = "fDxIBRnzQAhqI2AkZihEFzPCh0LQta7d2dJ5heSWwwVqf3Z1RjX26Cvndv2srO2U"


class Project(
    private val playerC: File,
    private var destination: File?,
    private var tmpDirUsed: File? = null,
    private val compilerAdditionalArgs: List<String> = listOf("-lm"),
    private val changeStdoutPrintfToStderrPrintf: Boolean = true,
    private val isForBenchmark: Boolean = true): AutoCloseable{

    class PlayerDotCCompileErrorException(message: String): Exception(message)

    lateinit var tmpDir: File
    private lateinit var tmpBombermanH: File
    private lateinit var tmpBombermanO: File
    private lateinit var tmpPlayerC: File

    private var isEnvSetup = false

    private fun createEnv(){

        val bombermanHInputStream = Project::class.java.getResourceAsStream("/bomberman.h")!!
        val bombermanOInputStream = Project::class.java.getResourceAsStream("/bomberman.o")!!

        tmpDir = tmpDirUsed ?: Files.createTempDirectory("bomberman").toFile()
        val tmpLevelDir = Files.createDirectory(File(tmpDir, "levels").toPath()).toFile()

        (0..2).forEach {
            val levelItInputStream = Project::class.java.getResourceAsStream("/levels/level$it.map")!!
            Files.copy(levelItInputStream, File(tmpLevelDir, "level$it.map").toPath())
        }

        tmpBombermanH = File(tmpDir, "bomberman.h")
        tmpBombermanO = File(tmpDir, "bomberman.o")
        tmpPlayerC = File(tmpDir, "player.c")
        Files.copy(bombermanHInputStream, tmpBombermanH.toPath())
        Files.copy(bombermanOInputStream, tmpBombermanO.toPath())

        val returnRegex = Regex("\\s*return\\s+(?<Action>[a-zA-Z\\d]+)\\s*;")
        val bombermanFunctionRegex = Regex("(?<BombermanFunction>action\\s*bomberman\\s*\\(.*?" +
                "tree\\s+(?<mapVarName>[a-zA-Z_\\d]+)" +
                ".*?" +
                "int\\s+(?<RemainingBombsVarName>[a-zA-Z_\\d]+).*?" +
                "int\\s+(?<ExplosionRangeVarName>[a-zA-Z_\\d]+).*?\\)\\s*\\{)", setOf(RegexOption.DOT_MATCHES_ALL))
        val printfStdoutRegex = Regex("(?<!f)printf\\s*\\((?<PrintfArgs>.*?)\\)")
        var playerCSource = playerC.readText()

        if(!isForBenchmark){
            tmpPlayerC.writeText(playerCSource)
            return
        }

        if(changeStdoutPrintfToStderrPrintf)
            playerCSource = playerCSource.replace(printfStdoutRegex) { "fprintf(stderr, ${it.groups["PrintfArgs"]?.value})" }

        val match = bombermanFunctionRegex.find(playerCSource)
        if(match != null){
            val bombermanFunction = match.groups["BombermanFunction"]!!.value
            val id = System.nanoTime()
            val startIdx = match.range.last
            var idx = startIdx + 1
            val stack = Stack<Char>()
            stack.add('{')
            while(stack.isNotEmpty()){
                if(playerCSource[idx] == '}')
                    stack.pop()
                if(playerCSource[idx] == '{')
                    stack.push('{')
                idx++
            }
            var endIdx = idx
            idx = startIdx

            var retMatch: MatchResult? = returnRegex.find(playerCSource, idx)

            while (retMatch != null){
                val replacement = """
                      printf("Action is: ");
                      switch(${retMatch.groups["Action"]!!.value}) {
                      case BOMBING:
                        printf("BOMBING\n");
                        break;
                      case NORTH:
                        printf("NORTH\n");
                        break;
                      case EAST:
                        printf("EAST\n");
                        break;
                      case SOUTH:
                        printf("SOUTH\n");
                        break;
                      case WEST:
                        printf("WEST\n");
                        break;
                      }
                      fflush(stdout);
                      ${retMatch.value}
                """.trimIndent()
                playerCSource = playerCSource.replaceRange((retMatch.range.first)..(retMatch.range.last), replacement)
                idx = retMatch.range.first + replacement.length
                endIdx += replacement.length - retMatch.value.length
                retMatch = if(idx < endIdx)
                    returnRegex.find(playerCSource, idx)
                else
                    null
            }

            playerCSource = playerCSource.replace(bombermanFunction, "int rounds_$id = 0;" +
                        "${bombermanFunction}printf(\"Rounds: %d\\n\", ++rounds_$id);printf(\"RemainingBombs: %d\\n\"," +
                    " ${match.groups["RemainingBombsVarName"]!!.value});fprintf(stderr, \"$STDERR_NEXT_ROUND_INDICATOR\"); fflush(stderr);")
            tmpPlayerC.writeText(playerCSource)
        } else {
            exitProcess(1)
        }

        isEnvSetup = true
    }

    override fun close() {
        deleteEnv()
    }

    fun deleteEnv(){
        tmpDir.deleteRecursively()
    }

    fun compile(): File{
        createEnv()

        if(destination == null)
            destination = File(tmpDir, "bomberman")

        var error: String

        runBlocking {
            error = executeCommand(this, listOf("gcc", "-Wall", *compilerAdditionalArgs.toTypedArray(),
                "-o", destination!!.absolutePath, tmpPlayerC.absolutePath,
                tmpBombermanO.absolutePath), stopAfterMilli = 2000)
        }

        print(error)

        if(error != "")
            throw PlayerDotCCompileErrorException("Remember that your player.c MUST compile with -Wall.")

        return destination!!
    }
}

class GameConstants {
    companion object {
        const val BOMBERMAN = '@'
        const val WALL = '*'
        const val BREAKABLE_WALL = '='
        const val PATH = '.'
        const val EXIT = 'E'
        const val BOMB = '#'
        const val BOMB_BONUS = 'B'
        const val FLAME_BONUS = 'F'
        const val FLAME_ENEMY = '&'
        const val GHOST_ENEMY = '%'
        const val BOMB_DELAY = 5
        const val BREAKABLE_WALL_SCORE = 10
        const val FLAME_ENEMY_SCORE = 50
        const val GHOST_ENEMY_SCORE = 200
        const val BOMB_BONUS_SCORE = 50
        const val FLAME_BONUS_SCORE = 50
    }
}

@Serializable
enum class GameResult {
    ERROR,
    TIMEOUT,
    WIN,
    LOSS,
    UNKNOWN;

    fun isValidGame() = this == WIN || this == LOSS
}

@Serializable
enum class Action { BOMBING, NORTH, EAST, SOUTH, WEST; }

class BombermanGameResultBuilder(val saveANSIColor: Boolean,
                                 val onFinishMapDrawCallBack: BombermanGameResultBuilder.() -> Unit) {
    private var score: Int = -1
    private var level: Int = -1
    private var totalBreakableWall: Int = -1
    private var brokenWall: Int = -1
    private var totalFlameEnemy: Int = -1
    private var flameEnemyKilled: Int = -1
    private var totalGhostEnemy: Int = -1
    private var ghostEnemyKilled: Int = -1
    private var bombBonusTaken: Int = -1
    private var flameBonusTaken: Int = -1
    private var result: GameResult = GameResult.UNKNOWN
    private var rounds: Int = -1
    private var signal: String? = null
    private var signalTrace: String? = null
    var remainingBombs = -1

    private val history: MutableList<MutableList<String>> = mutableListOf()
    private val actions: MutableList<Action> = mutableListOf()
    private var seed: Long = -1

    private var hasStarted = false
    private var receivedSignal = false
    private var firstMap: MutableList<String> = mutableListOf()
    private var firstMapIsDone = false
    private var previousLastMap: MutableList<String> = mutableListOf()
    private var lastMap: MutableList<String> = mutableListOf()
    private var lastMapWithANSI: MutableList<String> = mutableListOf()
    private var wasPreviousBorderTopBorder = false

    fun result(result: GameResult): BombermanGameResultBuilder{
        this.result = result
        return this
    }

    fun seed(seed: Long): BombermanGameResultBuilder{
        this.seed = seed
        return this
    }

    fun level(level: Int): BombermanGameResultBuilder{
        this.level = level
        return this
    }


    fun consume(rawLine: String, sep: Char){

        val rawLinePlusSep = rawLine + sep
        val line = rawLine.withoutANSIEscapeCode()

        val scoreRegex = Regex(".*SCORE: (?<Score>\\d+)\\s*\$")
        val gameOverRegex = Regex("\\s*\\\\____/\\\\__,_\\|_\\| \\|_\\| \\|_\\" +
                "|\\\\___\\| {2}\\\\___/ {2}\\\\_/ \\\\___\\|_\\|\\s*$")
        val mapBorderRegex = Regex("^\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\$")
        val congratulationsRegex = Regex("^\\s*\\\\____/\\\\___/\\|_\\| \\|_\\|\\\\__, \\|_\\|  " +
                "\\\\__,_\\|\\\\__\\|\\\\__,_\\|_\\|\\\\__,_\\|\\\\__\\|_\\|\\\\___/\\|_\\| \\|_\\|___\\(_\\)$")
        val startOfGameRegex = Regex("^Artificial Intelligence by .*$")

        val receivedSignalRegex = Regex("^Program received signal (?<Signal>[A-Z]*).*\$")

        val receivedStackTraceRegex = Regex("^0[xX][0-9a-fA-F]+\\s+in\\s+(?<FunctionName>.*)\\s\\(\\)$")

        val roundsRegex = Regex("^Rounds: (?<Rounds>\\d+)$")
        val remainingBombsRegex = Regex("^RemainingBombs: (?<RemainingBombs>\\d+)$")
        val actionRegex = Regex("^Action is: (?<Action>${Action.entries.toTypedArray().joinToString("|")})$")

        var match: MatchResult? = receivedSignalRegex.matchEntire(line)
        if(match != null){
            result = GameResult.ERROR
            receivedSignal = true
            signal = match.groups["Signal"]?.value
            return
        }

        match = startOfGameRegex.matchEntire(line)
        if(match != null){
            hasStarted = true
            return
        }

        if(!hasStarted)
            return

        match = receivedStackTraceRegex.matchEntire(line)
        if(match != null && receivedSignal){
            signalTrace = match.groups["FunctionName"]?.value
            return
        }

        match = roundsRegex.matchEntire(line)
        if(match != null){
            rounds = match.groups["Rounds"]!!.value.toInt()
            return
        }

        match = actionRegex.matchEntire(line)
        if(match != null){
            actions.add(Action.valueOf(match.groups["Action"]!!.value))
            history.add(if(saveANSIColor) lastMapWithANSI.copy() else lastMap.copy())

            if(history.size < 2){
                onFinishMapDrawCallBack(this)
                return
            }

            val previousLastMap = history[history.size - 2]
            val lastMap = history.last()

            if(totalBreakableWall == -1)
                totalBreakableWall = countOnMap(firstMap, GameConstants.BREAKABLE_WALL)
            brokenWall = totalBreakableWall - countOnMap(lastMap, GameConstants.BREAKABLE_WALL)

            if(totalFlameEnemy == -1)
                totalFlameEnemy = countOnMap(firstMap, GameConstants.FLAME_ENEMY)
            flameEnemyKilled = totalFlameEnemy - countOnMap(lastMap, GameConstants.FLAME_ENEMY)

            if(totalGhostEnemy == -1)
                totalGhostEnemy = countOnMap(firstMap, GameConstants.GHOST_ENEMY)
            ghostEnemyKilled = totalGhostEnemy - countOnMap(lastMap, GameConstants.GHOST_ENEMY)

            val ghostEnemyPos = pos(lastMap, GameConstants.GHOST_ENEMY)
            val previousGhostEnemyPost = pos(previousLastMap, GameConstants.GHOST_ENEMY)

            /**
             * An annoying bug occurs when a ghost enemy and something else
             * overlap. We can easily fix it for walls as the ghost enemy
             * will be shown instead of the wall.
             * However, for a bomb enemy, it's the bomb enemy that will overlap.
             * And as both enemy moves, it's impossible to track their coordinate.
             */
            if(ghostEnemyPos != null && previousGhostEnemyPost != null){
                val block = previousLastMap[ghostEnemyPos.first][ghostEnemyPos.second]
                when(block){
                    GameConstants.BREAKABLE_WALL -> {
                        brokenWall = totalBreakableWall - countOnMap(lastMap, GameConstants.BREAKABLE_WALL) - 1
                    }
                }
            }

            onFinishMapDrawCallBack(this)
            return
        }

        match = remainingBombsRegex.matchEntire(line)
        if(match != null){
            remainingBombs = match.groups["RemainingBombs"]!!.value.toInt()
            return
        }

        match = gameOverRegex.matchEntire(line)
        if(match != null){
            result = GameResult.LOSS
            return
        }

        match = congratulationsRegex.matchEntire(line)
        if(match != null){
            result = GameResult.WIN
            return
        }

        match = scoreRegex.matchEntire(line)
        if(match != null){
            score = match.groups["Score"]!!.value.toInt()
            return
        }

        match = mapBorderRegex.matchEntire(line)
        if(match != null){
            if(!wasPreviousBorderTopBorder){
                if(!firstMapIsDone){
                    firstMap.add(line)
                }
                lastMap.add(line)
                lastMapWithANSI.add(rawLinePlusSep)
                if(previousLastMap.isNotEmpty()){
                    bombBonusTaken = if(bombBonusTaken == -1) 0 else bombBonusTaken + countOnMap(previousLastMap, GameConstants.BOMB_BONUS) -
                            countOnMap(lastMap, GameConstants.BOMB_BONUS)

                    flameBonusTaken = if(flameBonusTaken == -1) 0 else flameBonusTaken + countOnMap(previousLastMap, GameConstants.FLAME_BONUS) -
                            countOnMap(lastMap, GameConstants.FLAME_BONUS)
                }
                previousLastMap = lastMap.copy()
                lastMap.clear()
                lastMapWithANSI.clear()
            } else {
                lastMap.add(line)
                lastMapWithANSI.add(rawLinePlusSep)
                firstMapIsDone = true
            }

            wasPreviousBorderTopBorder = !wasPreviousBorderTopBorder
        }

        if(wasPreviousBorderTopBorder){
            if(!firstMapIsDone)
                firstMap.add(line)
            lastMap.add(line)
            lastMapWithANSI.add(rawLinePlusSep)
        }
    }

    private fun pos(map: List<String>, searched: Char): Pair<Int, Int>?{
        for (y in map.indices){
            for(x in map[y].indices){
                if(map[y][x] == searched)
                    return Pair(y, x)
            }
        }
        return null
    }

    private fun countOnMap(map: List<String>, searched: Char): Int{
        var value = 0
        for(line in map){
            for(char in line)
                value += (char == searched).toInt()
        }
        return value
    }

    fun build(): BombermanGameResult {

        return BombermanGameResult(
            score,
            level,
            totalBreakableWall,
            brokenWall,
            totalFlameEnemy,
            flameEnemyKilled,
            totalGhostEnemy,
            ghostEnemyKilled,
            bombBonusTaken,
            flameBonusTaken,
            result,
            rounds,
            signal,
            signalTrace,
            seed,
            actions.copy(),
            history.map { it.copy() }
        )
    }



}

private fun <E> List<E>.copy(): MutableList<E> {
    return this.toMutableList()
}

@Serializable
data class BombermanGameResult(
    val score: Int,
    val level: Int,
    val totalBreakableWall: Int,
    val brokenWall: Int,
    val totalFlameEnemy: Int,
    val flameEnemyKilled: Int,
    val totalGhostEnemy: Int,
    val ghostEnemyKilled: Int,
    val bombBonusTaken: Int,
    val flameBonusTaken: Int,
    val result: GameResult,
    val rounds: Int,
    val signal: String?,
    val signalTrace: String?,
    val seed: Long,
    val actions: List<Action>?,
    val history: List<List<String>>?,
    val light: Boolean = false,
    val remainingBombs: Int = -1) {

    fun light(): BombermanGameResult {
        return BombermanGameResult(score, level, totalBreakableWall, brokenWall, totalFlameEnemy, flameEnemyKilled,
            totalGhostEnemy, ghostEnemyKilled, bombBonusTaken, flameBonusTaken, result, rounds, signal,
            signalTrace, seed, null, null, true
        )
    }

    fun partialBuildForPlayCommand(remainingBombs: Int = -1): BombermanGameResult {

        val finalActions = if(actions == null) null else {
            if(actions.isEmpty())
                emptyList()
            else
                listOf(actions.last())
        }

        return BombermanGameResult(score, level, totalBreakableWall, brokenWall, totalFlameEnemy, flameEnemyKilled,
            totalGhostEnemy, ghostEnemyKilled, bombBonusTaken, flameBonusTaken, result, rounds, signal, signalTrace,
            seed, finalActions, null, true, remainingBombs)
    }
}


/**
 * The mean results of [sampleSize] game(s) of Bomberman.
 */
@Serializable
data class BombermanBatchResult(
    val level: Int,
    val meanScore: Float,
    val meanTotalBreakableWall: Float,
    val meanBrokenWall: Float,
    val meanTotalFlameEnemy: Float,
    val meanFlameEnemyKilled: Float,
    val meanTotalGhostEnemy: Float,
    val meanGhostEnemyKilled: Float,
    val meanBombBonusTaken: Float,
    val meanFlameBonusTaken: Float,
    val winRate: Float,
    val results: Map<GameResult, Int>,
    val meanRounds: Float,
    val meanActions: Map<Action, Float>,
    val signals: Map<String, Int>,
    val sampleSize: Int,
    val games: List<BombermanGameResult>?) {

    fun prettyPrint() {
        println("""
        For level=$level:
            meanScore=$meanScore
            totalBreakableWall=$meanTotalBreakableWall
            meanBrokenWall=$meanBrokenWall
            meanTotalFlameEnemy=$meanTotalFlameEnemy
            meanFlameEnemyKilled=$meanFlameEnemyKilled
            totalGhostEnemy=$meanTotalGhostEnemy
            meanGhostEnemyKilled=$meanGhostEnemyKilled
            meanBombBonusTaken=$meanBombBonusTaken
            meanFlameBonusTaken=$meanFlameBonusTaken
            winRate=$winRate
            results=$results
            meanRounds=$meanRounds
            meanActions=$meanActions
            signals=$signals
            sampleSize=$sampleSize
            games=$games
    """.trimIndent())
    }

}

fun List<BombermanGameResult>.toBombermanBatchResult(withGames: List<BombermanGameResult>? = this): BombermanBatchResult {

    require(withGames == null || this.size == withGames.size)

    var gamesSize: Int = -1
    var games: Int = -1
    var level: Int = -1
    var meanScore = 0.0f
    var meanTotalBreakableWall = 0.0f
    var meanBrokenWall = 0.0f
    var meanTotalFlameEnemy = 0.0f
    var meanFlameEnemyKilled = 0.0f
    var meanTotalGhostEnemy = 0.0f
    var meanGhostEnemyKilled = 0.0f
    var meanBombBonusTaken = 0.0f
    var meanFlameBonusTaken = 0.0f
    var winRate = 0.0f
    val results: MutableMap<GameResult, Int> = mutableMapOf()
    var meanRounds = 0.0f
    val signals: MutableMap<String, Int> = mutableMapOf()
    val meanActions: MutableMap<Action, Float> = mutableMapOf(*Action.entries.map { it to 0f }.toTypedArray())

    for (game in this) {

        if(gamesSize == -1)
            gamesSize = this.size

        if(level == -1)
            level = game.level

        if(game.level != level){
            System.err.println("ERROR: Different levels on the same batch")
            exitProcess(1)
        }

        results[game.result] = results.getOrPut(game.result) { 0 } + 1

        if(game.signal != null)
            signals[game.signal] = signals.getOrPut(game.signal) { 0 } + 1

        if(!game.result.isValidGame())
            continue

        games = (if(games == -1) 0 else games) + 1
        meanScore += game.score
        meanTotalBreakableWall += game.totalBreakableWall
        meanBrokenWall += game.brokenWall
        meanTotalFlameEnemy += game.totalFlameEnemy
        meanFlameEnemyKilled += game.flameEnemyKilled
        meanTotalGhostEnemy += game.totalGhostEnemy
        meanGhostEnemyKilled += game.ghostEnemyKilled
        meanBombBonusTaken += game.bombBonusTaken
        meanFlameBonusTaken += game.flameBonusTaken
        winRate += if (game.result == GameResult.WIN) 1 else 0
        meanRounds += game.rounds
        meanActions.keys.forEach {
            if(!game.light)
                meanActions[it] = meanActions[it]!! + game.actions!!.count { action -> it == action }
        }
    }

    val totalGames = this.size.toFloat()
    meanScore /= totalGames
    meanTotalBreakableWall /= totalGames
    meanBrokenWall /= totalGames
    meanTotalFlameEnemy /= totalGames
    meanFlameEnemyKilled /= totalGames
    meanTotalGhostEnemy /= totalGames
    meanGhostEnemyKilled /= totalGames
    meanBombBonusTaken /= totalGames
    meanFlameBonusTaken /= totalGames
    winRate = winRate / totalGames * 100
    meanRounds /= totalGames
    meanActions.keys.forEach {
        meanActions[it] = meanActions[it]!! / totalGames
    }

    return BombermanBatchResult(level, meanScore, meanTotalBreakableWall, meanBrokenWall, meanTotalFlameEnemy, meanFlameEnemyKilled,
        meanTotalGhostEnemy, meanGhostEnemyKilled, meanBombBonusTaken, meanFlameBonusTaken, winRate, results, meanRounds, meanActions,
        signals, gamesSize, withGames)
}

class Bomberman(
    executable: File,
    tmpDir: File,
    delay: Int,
    displayType: DisplayType,
    level: Int,
    timeoutAfter: Long = Long.MAX_VALUE,
    seed: Long = System.nanoTime(),
    saveLineWithAnsi: Boolean = false,
    private val fullGameInsightMode: Boolean = false,
    ): Executable<BombermanGameResult>("gdb", timeoutAfter,
    "set confirm off\nb srand\nr\ncall x = $seed\nc\nq\n",
    false) {
    override var construction: BombermanGameResult? = null
    private val fullCallback: BombermanGameResultBuilder.() -> Unit = {
        if(fullGameInsightMode)
            totalGameHistory.add(builder.build().partialBuildForPlayCommand(builder.remainingBombs))
    }
    private val builder = BombermanGameResultBuilder(saveLineWithAnsi, if(fullGameInsightMode) fullCallback else { {} })
    val totalGameHistory: MutableList<BombermanGameResult> = mutableListOf()
    private val stderrRoundStringBuilder = StringBuilder()
    val totalStderrHistory: MutableList<String> = mutableListOf()

    init {
        builder.seed(seed)
        builder.level(level)

        args.add("--args")
        args.add(executable.absolutePath)
        args.add("-delay")
        args.add(delay.toString())
        args.add("-debug")
        args.add("off")
        args.add("-display")
        args.add(displayType.asCLIArg())
        args.add(File(File(tmpDir, "levels"), "level$level.map").absolutePath)
    }

    override suspend fun parseStdoutLine(
        line: String,
        sep: Char,
        stdout: BufferedReader,
        stderr: BufferedReader,
        stdin: BufferedWriter
    ) {
        builder.consume(line, sep)
    }

    override suspend fun parseStderrLine(
        line: String,
        sep: Char,
        stdout: BufferedReader,
        stderr: BufferedReader,
        stdin: BufferedWriter
    ) {
        if(fullGameInsightMode)
            if(sep != (-1).toChar())
                stderrRoundStringBuilder.append(line + sep)
            else
                stderrRoundStringBuilder.append(line)
    }

    private fun constructStderr(){
        val split = stderrRoundStringBuilder.toString().split(STDERR_NEXT_ROUND_INDICATOR)
        var i = 1
        while (i < split.size){
            totalStderrHistory.add(split[i])
            i++
        }
    }

    override fun onFinish() {
        constructStderr()
        construction = builder.build()
    }

    override fun onTimeout() {
        constructStderr()
        builder.result(GameResult.TIMEOUT)
        construction = builder.build()
    }

}

private fun String.withoutANSIEscapeCode(): String {
    return this.replace(Regex("\u001B\\[[;\\d]*[a-zA-Z]"), "")
}

private fun Boolean.toInt(): Int {
    return if(this) 1 else 0
}
package hello

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import kotlin.math.pow

@SpringBootApplication
class KotlinApplication {

    var myId: String = ""
    val detectDistance = 3
    var previousCoordinate = Pair(0,0)
    var previousAction: Action = Action.L

    @Bean
    fun routes() = router {
        GET {
            ServerResponse.ok().body(Mono.just("Let the battle begin!"))
        }

        POST("/**", accept(APPLICATION_JSON)) { request ->
            request.bodyToMono(ArenaUpdate::class.java).flatMap { arenaUpdate ->
                val (link, arenaState) = arenaUpdate
                val (dims, state) = arenaState

                if(myId.isEmpty()) myId = link.self.href

                val myState = findMyStateById(state, myId)
                if (myState == null) {
                    ServerResponse.ok().body(Mono.just(listOf("F", "R", "L", "T").random()))
                } else {
                    val (x, y, direction, _, wasHit) = myState
                    val actions = getAvailableActionsByLocation(x, y, direction, dims)
                    val canThrow = actions.contains(Action.T)
                    val canMoveForward = actions.contains(Action.F)

                    val enemyAroundMeMap = getEnemiesAroundMe(state, Pair(x,y))
                    val action = if (enemyAroundMeMap.isNotEmpty()) {
                        getNextAction(enemyAroundMeMap, direction, x, y, wasHit, canThrow, canMoveForward, dims)
                    } else if (canMoveForward && !pathBeingBlocked(Pair(x,y))) {
                        Action.F
                    } else {
                        listOf(Action.L, Action.R).random()
                    }

                    previousAction = action
                    println(action)
                    previousCoordinate = Pair(x,y)
                    ServerResponse.ok().body(Mono.just(action.name))
                }
            }
        }
    }

    fun getEnemiesAroundMe(stateMap: Map<String, PlayerState>, myCoordinate: Pair<Int, Int>, distance: Int? = detectDistance): Map<String, PlayerState> {
        return stateMap.filter{(id, state) ->
            id != myId &&
                    kotlin.math.abs(state.x - myCoordinate.first) <= distance!! &&
                    kotlin.math.abs(state.y - myCoordinate.second) <= distance
        }
    }

    fun calculateDistance(me: Pair<Int, Int>, enemy: Pair<Int, Int> ): Double = kotlin.math.sqrt((enemy.first - me.first ).toDouble().pow(2) + (enemy.second - me.second ).toDouble().pow(2))

    fun getNextAction(enemyAroundMeMap: Map<String, PlayerState>, direction: Direction, x: Int, y: Int, wasHit: Boolean, throwable: Boolean, canMoveForward: Boolean, dims: List<Int>): Action {
        var canThrow = false
        var turnLeftOrRight: Action? = null
        val enemyCoordinates = mutableListOf<Pair<Int,Int>>()

        for(enemyState in enemyAroundMeMap.values) {
            if (throwable) {
                if (!canThrow) {
                    when {
                        direction == Direction.W && (x - enemyState.x in 1 .. detectDistance && y == enemyState.y) -> canThrow = true
                        direction == Direction.E && (enemyState.x - x in 1 .. detectDistance && y == enemyState.y) -> canThrow = true
                        direction == Direction.N && (y - enemyState.y in 1..detectDistance && x == enemyState.x) -> canThrow = true
                        direction == Direction.S && (enemyState.y - y in 1 .. detectDistance && x == enemyState.x) -> canThrow = true
                    }
                }
                // check any enemies are within range but not facing
                if (!canThrow) {
                    when(direction) {
                        Direction.W -> if (enemyState.y - y in 1..detectDistance && x == enemyState.x) {
                            turnLeftOrRight = Action.L
                        } else if (y - enemyState.y in 1..detectDistance && x == enemyState.x){
                            turnLeftOrRight = Action.R
                        }

                        Direction.E -> if (enemyState.y - y in 1..detectDistance && x == enemyState.x) {
                            turnLeftOrRight = Action.R
                        } else if (y - enemyState.y in 1..detectDistance && x == enemyState.x){
                            turnLeftOrRight = Action.L
                        }

                        Direction.N -> if (enemyState.x - x in 1..detectDistance && y == enemyState.y) {
                            turnLeftOrRight = Action.R
                        } else if (x - enemyState.x in 1..detectDistance && y == enemyState.y) {
                            turnLeftOrRight = Action.L
                        }

                        Direction.S -> if (enemyState.x - x in 1..detectDistance && y == enemyState.y) {
                            turnLeftOrRight = Action.L
                        } else if (x - enemyState.x in 1..detectDistance && y == enemyState.y) {
                            turnLeftOrRight = Action.R
                        }
                    }
                }
            }
            if (!wasHit && canThrow) break

            if (wasHit) {
                when(enemyState.direction) {
                    Direction.W -> if (enemyState.x - x <= detectDistance && enemyState.y == y) enemyCoordinates.add(Pair(enemyState.x, enemyState.y))
                    Direction.E -> if (x - enemyState.x <= detectDistance && enemyState.y == y) enemyCoordinates.add(Pair(enemyState.x, enemyState.y))
                    Direction.N -> if (enemyState.y - y <= detectDistance && enemyState.x == x) enemyCoordinates.add(Pair(enemyState.x, enemyState.y))
                    Direction.S -> if (y - enemyState.y <= detectDistance && enemyState.x == x) enemyCoordinates.add(Pair(enemyState.x, enemyState.y))
                }
//                if (enemyCoordinate != null) break
            }
        }
        return if (enemyCoordinates.isNotEmpty()) {
            dodge(enemyCoordinates, Pair(x,y), direction, canMoveForward, dims)
        } else if (canThrow) {
            Action.T
        } else turnLeftOrRight ?: hunt(enemyAroundMeMap.values.toList(), Pair(x,y), direction, canMoveForward)
    }

    fun findMyStateById(state: Map<String, PlayerState>, myId: String): PlayerState? = state[myId]

    fun getAvailableActionsByLocation(x: Int, y: Int, direction: Direction, dims: List<Int>): List<Action> {
        if (dims.isEmpty()) return emptyList()
        // coordinate start at 0
        val maxW = dims[0] - 1
        val maxH = dims[1] - 1
        val allActions = Action.values().asList()

        // eliminate actions by x boundary
        var availableActions: List<Action> = when {
            x == 0 && direction == Direction.W -> allActions.filter { it != Action.F && it != Action.T }
            x == maxW && direction == Direction.E -> allActions.filter { it != Action.F && it != Action.T }
            else -> allActions
        }

        // eliminate actions by y boundary
        availableActions = when {
            y == 0 && direction == Direction.N -> availableActions.filter { it != Action.F && it != Action.T }
            y == maxH && direction == Direction.S -> availableActions.filter { it != Action.F && it != Action.T }
            else -> availableActions
        }

        return availableActions
    }

    fun dodge(enemies: List<Pair<Int,Int>>, myCoordinate: Pair<Int,Int>, direction: Direction, canMoveForward: Boolean, dims: List<Int>): Action {
        return if (!canMoveForward) {
            if (enemies.size == 1) {
                // y axis enemy
                if (enemies.first().first == myCoordinate.first ) {
                    //  up corner
                    if (enemies.first().second > myCoordinate.second ) {
                        // up corner (left or right)
                        if (myCoordinate.first == 0)  Action.R else Action.L
                    }  else {
                        // down corner (left or right)
                        if (myCoordinate.first == 0) Action.L else Action.R
                    }
                } else {
                    // x axis enemy
                    if ( enemies.first().first > myCoordinate.first ) {
                        // left up or down corner
                        if (myCoordinate.second == 0) Action.L else Action.R
                    } else {
                        // right up or down corner
                        if (myCoordinate.second == 0) Action.R else Action.L
                    }
                }
            } else {
                listOf(Action.L, Action.R).random()
            }
        } else {
            if (pathBeingBlocked(myCoordinate)) {
                listOf(Action.L, Action.R).random()
            } else {
                if (enemies.size == 1) dodgeByAxis(myCoordinate, enemies.first(), direction, dims) else dodgeByDirection(direction, myCoordinate, enemies)
            }
        }
    }

    // find enemy to attack in possible range, fall in this function when x & y coordinate are not same
    fun hunt(enemyAroundMeList: List<PlayerState>, myCoordinate: Pair<Int,Int>, direction: Direction, canMoveForward: Boolean): Action {
        val enemyListByDistance = enemyAroundMeList.sortedBy {
            calculateDistance(myCoordinate,Pair(it.x, it.y))
        }

        val target = enemyListByDistance.first()

        return if (canMoveForward) {
            when(direction) {
                Direction.W ->  if (target.x > myCoordinate.first)  {
                    if (target.y > myCoordinate.second) Action.L else Action.R
                } else Action.F

                Direction.E ->  if (target.x > myCoordinate.first) Action.F else {
                    if (target.y > myCoordinate.second) Action.R else Action.L
                }

                Direction.S ->  if (target.x > myCoordinate.first) {
                    if (target.y > myCoordinate.second) Action.F else Action.R
                } else {
                    if (target.y > myCoordinate.second) Action.F else Action.L
                }

                Direction.N ->  if (target.x > myCoordinate.first) {
                    if (target.y > myCoordinate.second) Action.R else Action.F
                } else {
                    if (target.y > myCoordinate.second) Action.L else Action.F
                }
            }
        } else {
            when(direction) {
                Direction.W -> if (target.y > myCoordinate.second) Action.L else Action.R
                Direction.E -> if (target.y > myCoordinate.second) Action.R else Action.L
                Direction.S -> if (target.x > myCoordinate.first) Action.L else Action.R
                Direction.N -> if (target.x > myCoordinate.first) Action.R else Action.L
            }
        }
    }

    fun pathBeingBlocked(currentCoordinate: Pair<Int, Int>): Boolean = previousAction == Action.F && previousCoordinate == currentCoordinate

    fun dodgeByAxis(myCoordinate: Pair<Int, Int>, enemy: Pair<Int,Int>, direction: Direction, dims: List<Int>): Action {
        // same axis will not go toward
        return if (enemy.first == myCoordinate.first) {
            when(direction) {
                Direction.N, Direction.S -> when (myCoordinate.first) {
                    0 -> Action.R
                    dims[0]-1 -> {
                        Action.L
                    }
                    else -> listOf(Action.L, Action.R).random()
                }

                else -> Action.F
            }
        } else {
            when(direction) {
                Direction.W, Direction.E ->  when (myCoordinate.second)  {
                    0 -> Action.L
                    dims[1]-1 -> Action.R
                    else -> listOf(Action.L, Action.R).random()
                }
                else -> Action.F
            }
        }
    }

    fun getActionPreventFromBlocked(currentCoordinate: Pair<Int, Int>): Action {
        val notPreviousAction = listOf(Action.L, Action.R).filterNot { previousAction == it }
        return if (currentCoordinate == previousCoordinate) previousAction else notPreviousAction.first()
    }
    fun dodgeByDirection(direction: Direction, myCoordinate: Pair<Int, Int>, enemyAroundMeList: List<Pair<Int,Int>>): Action {
        return when(direction) {
            Direction.W -> if (enemyAroundMeList.contains(Pair(myCoordinate.first - 1, myCoordinate.second))) getActionPreventFromBlocked(myCoordinate) else Action.F
            Direction.E -> if (enemyAroundMeList.contains(Pair(myCoordinate.first + 1, myCoordinate.second))) getActionPreventFromBlocked(myCoordinate) else Action.F
            Direction.N -> if (enemyAroundMeList.contains(Pair(myCoordinate.first, myCoordinate.second - 1))) getActionPreventFromBlocked(myCoordinate) else Action.F
            Direction.S -> if (enemyAroundMeList.contains(Pair(myCoordinate.first, myCoordinate.second + 1))) getActionPreventFromBlocked(myCoordinate) else Action.F
        }
    }

}

fun main(args: Array<String>) {
    runApplication<KotlinApplication>(*args)
}

enum class Direction {
    N, S, W, E
}

enum class Action {
    F, R, L, T
}

data class ArenaUpdate(val _links: Links, val arena: Arena)
data class PlayerState(val x: Int, val y: Int, val direction: Direction, val score: Int, val wasHit: Boolean)
data class Links(val self: Self)
data class Self(val href: String)
data class Arena(val dims: List<Int>, val state: Map<String, PlayerState>)

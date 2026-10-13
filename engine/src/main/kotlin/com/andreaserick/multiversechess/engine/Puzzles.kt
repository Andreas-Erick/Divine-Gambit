package com.andreaserick.multiversechess.engine

/** The built-in puzzles, roughly in order of difficulty. Each is checked by PuzzlesTest. */
object Puzzles {
    val all: List<Puzzle> = listOf(
        Puzzle(
            id = "rook-past",
            title = "Into the past",
            hint = "A king can step away from your pieces, but its past self can't. Where was it two turns ago?",
            solution = listOf(Pos.of(0, 4, "e1") to Pos.of(0, 4, "e8")),
        ) {
            board(
                "....k...",
                "ppp.....",
                "........",
                "........",
                "........",
                "........",
                ".....PPP",
                "....R.K.",
            )
            turns("g1h1", "e8d7", "h1g1", "d7c6")
        },
        Puzzle(
            id = "bishop-past",
            title = "Diagonal through time",
            hint = "A bishop can move diagonally between a rank and time: one rank and one turn per step.",
            solution = listOf(Pos.of(0, 4, "f4") to Pos.of(0, 4, "e5")),
        ) {
            board(
                "....k...",
                "ppp.....",
                "........",
                "........",
                ".....B..",
                "........",
                ".....PPP",
                "......K.",
            )
            turns("g1h1", "e8d7", "h1g1", "d7c6")
        },
        Puzzle(
            id = "knight-past",
            title = "Leap through time",
            hint = "Knights jump through time too: two squares along one dimension and one along another. " +
                "Where the king stands now will be its past after Black replies.",
            solution = listOf(Pos.of(0, 4, "b3") to Pos.of(0, 4, "d4")),
        ) {
            board(
                "....k...",
                "......pp",
                "........",
                "........",
                "........",
                ".N......",
                "PP......",
                ".K......",
            )
            turns("b1a1", "e8d7", "a1b1", "d7d6")
        },
        Puzzle(
            id = "guarded-rook",
            title = "Guarded",
            hint = "Threaten the king's past, but make sure its present self can't simply take your piece.",
            solution = listOf(Pos.of(0, 2, "e1") to Pos.of(0, 2, "e8")),
        ) {
            board(
                "....k...",
                "pp......",
                "........",
                ".......B",
                "........",
                "........",
                ".....PPP",
                "....R.K.",
            )
            turns("g1h1", "e8d7")
        },
        Puzzle(
            id = "crossing-over",
            title = "Crossing over",
            hint = "One move can play two boards: a piece that leaves one timeline and lands on the latest board " +
                "of another advances both.",
            solution = listOf(Pos.of(1, 6, "e8") to Pos.of(0, 6, "e8")),
        ) {
            board(
                "....k...",
                "pp......",
                "........",
                "........",
                "........",
                "........",
                "......PP",
                "R......K",
            )
            turns("a1a3", "a7a6")
            // The rook travels one turn back, creating timeline +1.
            move(Pos.of(0, 2, "a3"), Pos.of(0, 0, "a3"))
            submit()
            move("e8d7", l = 1)
            submit()
            move("a3e3", l = 1)
            submit()
            move("e8d7")
            move("b7b6", l = 1)
            submit()
            move("h1g1")
            move("e3e8", l = 1)
            submit()
            move("d7c6")
            move("d7c6", l = 1)
            submit()
        },
        Puzzle(
            id = "unicorn",
            title = "The unicorn",
            hint = "A unicorn moves the same distance along exactly three dimensions, such as timeline, file and rank, " +
                "or file, rank and time.",
            solution = listOf(Pos.of(1, 6, "g4") to Pos.of(0, 6, "f5")),
        ) {
            board(
                "....k...",
                "pp...p..",
                "........",
                "........",
                "........",
                ".......U",
                "......PP",
                ".......K",
            )
            turns("h1g1", "a7a6")
            // The unicorn travels one turn back (and one file and rank), creating timeline +1.
            move(Pos.of(0, 2, "h3"), Pos.of(0, 0, "g4"))
            submit()
            move("b7b6", l = 1)
            submit()
            move("h1g1", l = 1)
            submit()
            move("e8d7")
            move("e8d7", l = 1)
            submit()
            move("g1h1")
            move("g1h1", l = 1)
            submit()
            move("d7c6")
            move("d7c6", l = 1)
            submit()
        },
    )

    fun byId(id: String): Puzzle? = all.firstOrNull { it.id == id }
}

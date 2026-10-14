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
                "of another advances both. Black's king is boxed in on every board, past and present.",
            solution = listOf(Pos.of(-1, 4, "f5") to Pos.of(0, 4, "f7")),
        ) {
            board(
                ".r....rk",
                "......pp",
                "........",
                "........",
                "........",
                "....N...",
                "......PP",
                "......K.",
            )
            turns("e3f5", "b8b7", "f5e3")
            // The rook travels one turn back, creating timeline -1, where the knight is still on f5.
            // Black now has a timeline more than White, so Black's next one wouldn't move the present.
            move(Pos.of(0, 3, "b7"), Pos.of(0, 1, "b7"))
            submit()
            move("g1f1", l = -1)
            submit()
            move("b7c7", l = -1)
            submit()
        },
        Puzzle(
            id = "unicorn",
            title = "The unicorn",
            hint = "A unicorn moves the same distance along exactly three dimensions, such as timeline, file and rank, " +
                "or file, rank and time. It never attacks its own board, only others.",
            solution = listOf(Pos.of(-1, 6, "h6") to Pos.of(0, 6, "g7")),
        ) {
            board(
                "r.....nk",
                "p.....pp",
                ".....P..",
                "......U.",
                "........",
                "........",
                "......PP",
                "......K.",
            )
            turns("h2h3", "a8b8", "h3h4")
            // The rook travels one turn back, creating timeline -1.
            // Black now has a timeline more than White, so Black's next one wouldn't move the present.
            move(Pos.of(0, 3, "b8"), Pos.of(0, 1, "b8"))
            submit()
            move("h3h4", l = -1)
            submit()
            move("b8c8", l = -1)
            submit()
            // The unicorn leaves timeline 0 for -1, playing both boards.
            move(Pos.of(0, 4, "g5"), Pos.of(-1, 4, "h6"))
            submit()
            move("g7g6")
            move("a7a6", l = -1)
            submit()
        },
    )

    fun byId(id: String): Puzzle? = all.firstOrNull { it.id == id }
}

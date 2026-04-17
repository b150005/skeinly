package io.github.b150005.knitnote.domain.symbol

/**
 * Top-level JIS L 0201-1995 taxonomy for knitting symbols. Phase 30 covers [KNIT];
 * later phases add [CROCHET], [AFGHAN], and [MACHINE] under the same catalog interface.
 */
enum class SymbolCategory(
    val jaLabel: String,
    val enLabel: String,
) {
    KNIT(jaLabel = "棒針編目", enLabel = "Knitting needle"),
    CROCHET(jaLabel = "かぎ針編目", enLabel = "Crochet"),
    AFGHAN(jaLabel = "アフガン編目", enLabel = "Afghan"),
    MACHINE(jaLabel = "家庭用編機編目", enLabel = "Home knitting machine"),
}

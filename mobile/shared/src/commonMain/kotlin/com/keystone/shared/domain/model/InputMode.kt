package com.keystone.shared.domain.model

/** How the employee asked: typed, or spoken (then the answer is also read aloud). */
enum class InputMode(internal val wire: String) {
    Text("text"),
    Voice("voice"),
}

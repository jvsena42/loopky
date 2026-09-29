package com.github.jvsena42.loopky.cli

/**
 * `loopky <command> --help`, and what a usage error prints under its message.
 *
 * Generated from [cliCommands], so it cannot describe a flag the parser does not take. A usage error
 * used to print the whole manual — hundreds of lines on stderr for one wrong flag, the one line that
 * mattered scrolled off the top.
 */
internal fun commandHelp(command: CliCommand): String = buildString {
    appendLine("loopky ${command.synopsis()}")
    appendLine("  ${command.summary}")
    if (command.options.isNotEmpty()) {
        appendLine()
        appendLine("OPTIONS")
        val labels = command.options.map { it.label() }
        val width = labels.maxOf { it.length }
        command.options.zip(labels).forEach { (option, label) ->
            appendLine("  ${label.padEnd(width)}  ${option.summary}")
        }
    }
    appendLine()
    append("Global options, file formats and exit codes: loopky --help")
}

internal fun commandFor(verb: String): CliCommand? = cliCommands().firstOrNull { it.path == verb }

private fun CliCommand.synopsis(): String {
    val operands = when (val o = operand) {
        Operand.None -> ""
        Operand.Path -> " <file>"
        is Operand.OneOf -> " ${o.choices.joinToString("|")}"
        is Operand.Opaque -> (o.required.map { " <$it>" } + o.optional.map { " [$it]" }).joinToString("")
    }
    val options = if (options.isEmpty()) "" else " [options]"
    return "$path$operands$options"
}

private fun CliOption.label(): String = when (val v = value) {
    OptionValue.Switch -> "--$name"
    OptionValue.Text -> "--$name VALUE"
    OptionValue.Path -> "--$name FILE"
    is OptionValue.OneOf -> "--$name ${v.choices.joinToString("|")}"
}

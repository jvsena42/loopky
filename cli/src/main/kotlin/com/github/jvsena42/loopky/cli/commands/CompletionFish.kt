package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliCommand
import com.github.jvsena42.loopky.cli.CliOption
import com.github.jvsena42.loopky.cli.GLOBAL_OPTIONS
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.cliCommands
import com.github.jvsena42.loopky.cli.commandGroups
import com.github.jvsena42.loopky.cli.topLevelWords

/**
 * The fish script.
 *
 * Declarative, so there is no dispatch to generate: one `complete` line per option, each guarded by
 * a condition asking what command is being typed.
 *
 * Generated from the table in `CommandSurface.kt`; see [completion] for why.
 */
internal fun fishCompletion(): String = buildString {
    append(header("fish"))
    appendLine()
    append(fishHelper())
    appendLine()
    appendLine("# No file completion unless a command asks for it.")
    appendLine("complete -c loopky -f")
    appendLine()
    appendLine("# Top-level commands.")
    topLevelWords().forEach { (word, summary) ->
        appendLine("complete -c loopky -n '__loopky_is \"\"' -a $word -d '$summary'")
    }
    commandGroups().forEach { (group, commands) ->
        appendLine()
        appendLine("# $group")
        commands.forEach { command ->
            val verb = command.path.substringAfter(' ')
            appendLine("complete -c loopky -n '__loopky_is $group' -a $verb -d '${command.summary}'")
        }
    }
    cliCommands().filter { it.options.isNotEmpty() || it.operand != Operand.None }.forEach { command ->
        appendLine()
        appendLine("# loopky ${command.path}")
        command.options.forEach { appendLine(fishOption("__loopky_is \"${command.path}\"", it)) }
        fishOperand(command)?.let { appendLine(it) }
    }
    appendLine()
    appendLine("# Accepted by every command.")
    GLOBAL_OPTIONS.forEach { appendLine(fishOption(condition = null, option = it)) }
}

/**
 * The command being typed, computed the same way the other two scripts compute it.
 *
 * fish's own `__fish_seen_subcommand_from` would nearly do, but it answers yes to a word anywhere
 * on the line — including one that is the *value* of an option — so `--title create` would put
 * `deck create`'s flags on a `deck edit`.
 */
private fun fishHelper(): String = """
    |function __loopky_command --description 'the loopky command on the current line'
    |    set -l tokens (commandline -opc)
    |    set -e tokens[1]
    |    set -l value_opts ${valueTakingWords()}
    |    set -l positional
    |    set -l skip 0
    |    for tok in ${'$'}tokens
    |        if test ${'$'}skip -eq 1
    |            set skip 0
    |        else if string match -q -- '--*=*' ${'$'}tok
    |            true
    |        else if string match -q -- '--*' ${'$'}tok
    |            if contains -- ${'$'}tok ${'$'}value_opts
    |                set skip 1
    |            end
    |        else
    |            set -a positional ${'$'}tok
    |        end
    |    end
    |    set -l cmd ""
    |    if set -q positional[1]
    |        set cmd ${'$'}positional[1]
    |        switch ${'$'}cmd
    |            case ${commandGroups().keys.joinToString(" ")}
    |                if set -q positional[2]
    |                    set cmd "${'$'}cmd ${'$'}positional[2]"
    |                end
    |        end
    |    end
    |    echo ${'$'}cmd
    |end
    |
    |function __loopky_is --description 'is the current command the given one'
    |    set -l cmd (__loopky_command)
    |    test "${'$'}cmd" = "${'$'}argv[1]"
    |end
    |
""".trimMargin()

private fun fishOption(condition: String?, option: CliOption): String = buildString {
    append("complete -c loopky")
    if (condition != null) append(" -n '$condition'")
    append(" -l ${option.name}")
    when (val value = option.value) {
        OptionValue.Switch -> Unit
        OptionValue.Text -> append(" -r")
        OptionValue.Path -> append(" -r -F")
        is OptionValue.OneOf -> append(" -x -a '${value.choices.joinToString(" ")}'")
    }
    append(" -d '${option.summary}'")
}

private fun fishOperand(command: CliCommand): String? = when (val operand = command.operand) {
    Operand.None, is Operand.Opaque -> null
    Operand.Path -> "complete -c loopky -n '__loopky_is \"${command.path}\"' -F"
    is Operand.OneOf ->
        "complete -c loopky -n '__loopky_is \"${command.path}\"' -a '${operand.choices.joinToString(" ")}'"
}

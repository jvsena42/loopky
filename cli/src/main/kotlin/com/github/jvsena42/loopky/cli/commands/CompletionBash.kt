package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliCommand
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.cliCommands
import com.github.jvsena42.loopky.cli.commandGroups
import com.github.jvsena42.loopky.cli.topLevelWords

/**
 * The bash script.
 *
 * `complete -F` and one function: bash has no declarative form, so the whole surface is a `case`
 * over the command the function works out for itself.
 *
 * Generated from the table in `CommandSurface.kt`; see [completion] for why.
 */
internal fun bashCompletion(): String = buildString {
    append(header("bash"))
    appendLine()
    appendLine("_loopky() {")
    appendLine("    local cur prev cmd tok i skip")
    appendLine("    cur=\"\${COMP_WORDS[COMP_CWORD]}\"")
    appendLine("    prev=\"\${COMP_WORDS[COMP_CWORD-1]}\"")
    appendLine()
    appendLine("    # An option that takes a value: complete the value, never another option.")
    appendLine("    case \"\$prev\" in")
    append(bashValueArms())
    appendLine("    esac")
    appendLine()
    append(BASH_WALK)
    appendLine()
    appendLine("    case \"\$cmd\" in")
    append(bashCommandArms())
    appendLine("    esac")
    appendLine("}")
    appendLine()
    appendLine("complete -F _loopky loopky")
}

private fun bashValueArms(): String = buildString {
    optionsByValue().forEach { (value, names) ->
        val pattern = names.joinToString("|")
        when (value) {
            is OptionValue.OneOf ->
                appendLine("        $pattern) COMPREPLY=( \$(compgen -W \"${value.choices.joinToString(" ")}\" -- \"\$cur\") ); return ;;")

            OptionValue.Path ->
                appendLine("        $pattern) compopt -o filenames 2>/dev/null; COMPREPLY=( \$(compgen -f -- \"\$cur\") ); return ;;")

            // Nothing local can guess a deck title, so offer nothing rather than the filenames
            // bash falls back to — a title completed from the working directory is a wrong answer
            // that looks like a working feature.
            OptionValue.Text -> appendLine("        $pattern) COMPREPLY=(); return ;;")
            OptionValue.Switch -> Unit
        }
    }
}

/**
 * Find the command by walking the words already typed, stepping over every option and its value.
 *
 * Written out rather than assumed to be "the first one or two words": `loopky --env staging deck
 * create` is a legal line, and taking `staging` for the command would complete nothing at all.
 */
private val BASH_WALK = """
    |    local -a _loopky_words=()
    |    skip=0
    |    i=1
    |    while [ "${'$'}i" -lt "${'$'}COMP_CWORD" ]; do
    |        tok="${'$'}{COMP_WORDS[${'$'}i]}"
    |        if [ "${'$'}skip" = 1 ]; then
    |            skip=0
    |        elif [[ "${'$'}tok" == --*=* ]]; then
    |            :
    |        elif [[ "${'$'}tok" == --* ]]; then
    |            case " ${valueTakingWords()} " in
    |                *" ${'$'}tok "*) skip=1 ;;
    |            esac
    |        else
    |            _loopky_words+=("${'$'}tok")
    |        fi
    |        i=${'$'}((i + 1))
    |    done
    |
    |    cmd="${'$'}{_loopky_words[0]-}"
    |    case "${'$'}cmd" in
    |        ${commandGroups().keys.joinToString("|")})
    |            if [ "${'$'}{#_loopky_words[@]}" -ge 2 ]; then
    |                cmd="${'$'}cmd ${'$'}{_loopky_words[1]}"
    |            fi
    |            ;;
    |    esac
    |
""".trimMargin()

private fun bashCommandArms(): String = buildString {
    appendLine("        \"\")")
    appendLine(bashOffer(topLevelWords().joinToString(" ") { it.first }, globalWords()))
    appendLine("            ;;")
    commandGroups().forEach { (group, commands) ->
        val verbs = commands.joinToString(" ") { it.path.substringAfter(' ') }
        appendLine("        $group)")
        appendLine(bashOffer(verbs, globalWords()))
        appendLine("            ;;")
    }
    cliCommands().forEach { command ->
        appendLine("        \"${command.path}\")")
        appendLine(bashArmBody(command))
        appendLine("            ;;")
    }
}

/** A word list for a bare word and the option list when the current word starts with a dash. */
private fun bashOffer(words: String, options: String): String = """
    |            if [[ "${'$'}cur" == -* ]]; then
    |                COMPREPLY=( ${'$'}(compgen -W "$options" -- "${'$'}cur") )
    |            else
    |                COMPREPLY=( ${'$'}(compgen -W "$words" -- "${'$'}cur") )
    |            fi
""".trimMargin()

private fun bashArmBody(command: CliCommand): String = when (val operand = command.operand) {
    is Operand.OneOf -> bashOffer(operand.choices.joinToString(" "), optionWords(command))

    Operand.Path -> """
        |            if [[ "${'$'}cur" == -* ]]; then
        |                COMPREPLY=( ${'$'}(compgen -W "${optionWords(command)}" -- "${'$'}cur") )
        |            else
        |                compopt -o filenames 2>/dev/null
        |                COMPREPLY=( ${'$'}(compgen -f -- "${'$'}cur") )
        |            fi
    """.trimMargin()

    // An id, or nothing at all: offer the options and let a bare word complete to nothing, which
    // is honest. Falling back to filenames here would suggest the working directory as deck ids.
    is Operand.Opaque, Operand.None ->
        "            COMPREPLY=( \$(compgen -W \"${optionWords(command)}\" -- \"\$cur\") )"
}

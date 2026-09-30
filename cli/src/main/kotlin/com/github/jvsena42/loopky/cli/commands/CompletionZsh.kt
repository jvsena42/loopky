package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliOption
import com.github.jvsena42.loopky.cli.GLOBAL_OPTIONS
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.cliCommands
import com.github.jvsena42.loopky.cli.commandGroups
import com.github.jvsena42.loopky.cli.topLevelWords

/**
 * The zsh script.
 *
 * The one of the three that carries descriptions into the menu, which is most of what makes zsh
 * completion worth having — every option and command arrives with its summary beside it.
 *
 * Generated from the table in `CommandSurface.kt`; see [completion] for why.
 */
internal fun zshCompletion(): String = buildString {
    appendLine("#compdef loopky")
    append(header("zsh"))
    appendLine()
    append(ZSH_HELPER)
    appendLine()
    appendLine("_loopky() {")
    appendLine("    local cur prev cmd tok i skip")
    appendLine("    cur=\"\${words[CURRENT]}\"")
    appendLine("    prev=\"\${words[CURRENT-1]}\"")
    appendLine()
    appendLine("    case \"\$prev\" in")
    append(zshValueArms())
    appendLine("    esac")
    appendLine()
    append(ZSH_WALK)
    appendLine()
    appendLine("    case \"\$cmd\" in")
    append(zshCommandArms())
    appendLine("    esac")
    appendLine("}")
    appendLine()
    append(ZSH_TAIL)
}

/**
 * The two ways a zsh completion is installed need opposite endings, and this is the guard that
 * serves both.
 *
 * Dropped into `$fpath` as `_loopky`, the `#compdef` tag makes zsh *autoload* the file and expect
 * the function to run — so the file has to call it. `eval`'d from `.zshrc`, that same call runs
 * `_loopky` with no completion context at all: `_describe` and `_message` are not defined outside
 * one, and the user gets a handful of "command not found" lines every time a shell opens.
 * `funcstack[1]` is how the function tells which of the two happened.
 */
private val ZSH_TAIL = """
    |if [[ "${'$'}funcstack[1]" == "_loopky" ]]; then
    |    _loopky "${'$'}@"
    |else
    |    compdef _loopky loopky
    |fi
    |
""".trimMargin()

private val ZSH_HELPER = """
    |_loopky_describe() {
    |    local tag="${'$'}1" label="${'$'}2"
    |    shift 2
    |    local -a entries
    |    entries=("${'$'}@")
    |    _describe -t "${'$'}tag" "${'$'}label" entries
    |}
    |
""".trimMargin()

private fun zshValueArms(): String = buildString {
    optionsByValue().forEach { (value, names) ->
        val pattern = names.joinToString("|")
        when (value) {
            is OptionValue.OneOf ->
                appendLine("        $pattern) _values 'value' ${value.choices.joinToString(" ")}; return ;;")

            OptionValue.Path -> appendLine("        $pattern) _files; return ;;")
            OptionValue.Text -> appendLine("        $pattern) _message -e value 'value'; return ;;")
            OptionValue.Switch -> Unit
        }
    }
}

private val ZSH_WALK = """
    |    local -a positional
    |    skip=0
    |    i=2
    |    while (( i < CURRENT )); do
    |        tok="${'$'}{words[i]}"
    |        if (( skip )); then
    |            skip=0
    |        elif [[ "${'$'}tok" == --*=* ]]; then
    |            :
    |        elif [[ "${'$'}tok" == --* ]]; then
    |            case " ${valueTakingWords()} " in
    |                *" ${'$'}tok "*) skip=1 ;;
    |            esac
    |        else
    |            positional+=("${'$'}tok")
    |        fi
    |        (( i++ ))
    |    done
    |
    |    cmd="${'$'}{positional[1]:-}"
    |    case "${'$'}cmd" in
    |        ${commandGroups().keys.joinToString("|")})
    |            if [[ -n "${'$'}{positional[2]:-}" ]]; then
    |                cmd="${'$'}cmd ${'$'}{positional[2]}"
    |            fi
    |            ;;
    |    esac
    |
""".trimMargin()

private fun zshCommandArms(): String = buildString {
    appendLine("        \"\")")
    appendLine(zshDescribe("commands", "command", topLevelWords().map { "${it.first}:${it.second}" }))
    appendLine(zshDescribe("options", "option", GLOBAL_OPTIONS.map { it.entry() }))
    appendLine("            ;;")
    commandGroups().forEach { (group, commands) ->
        appendLine("        $group)")
        appendLine(zshDescribe("commands", "command", commands.map { "${it.path.substringAfter(' ')}:${it.summary}" }))
        appendLine(zshDescribe("options", "option", GLOBAL_OPTIONS.map { it.entry() }))
        appendLine("            ;;")
    }
    cliCommands().forEach { command ->
        appendLine("        \"${command.path}\")")
        appendLine(zshDescribe("options", "option", (command.options + GLOBAL_OPTIONS).map { it.entry() }))
        zshOperand(command.operand)?.let { appendLine(it) }
        appendLine("            ;;")
    }
}

/** `_describe` takes `word:description` pairs, so a colon in a description would split it. */
private fun CliOption.entry(): String = "--$name:$summary"

private fun zshDescribe(tag: String, label: String, entries: List<String>): String =
    "            _loopky_describe $tag $label " + entries.joinToString(" ") { "'$it'" }

private fun zshOperand(operand: Operand): String? = when (operand) {
    Operand.None -> null
    Operand.Path -> "            _files"
    is Operand.OneOf -> "            _loopky_describe values value " +
        operand.choices.joinToString(" ") { "'$it'" }
    // Nothing to offer, but saying what the word is beats an empty menu that reads like a bug.
    is Operand.Opaque -> "            _message -e operand '${operand.names.joinToString(" ")}'"
}

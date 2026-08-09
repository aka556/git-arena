package org.xiaoyu.gitarena.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void parsesPipesAndRedirectsAsControlledTokens() {
        ParsedCommand command = parser.parse("echo \"hello world\" | grep hello > out.txt");

        assertThat(command.program()).isEqualTo("echo");
        assertThat(command.args()).containsExactly("hello world", "|", "grep", "hello", ">", "out.txt");
    }

    @Test
    void rejectsLogicalOr() {
        assertThatThrownBy(() -> parser.parse("echo ok || echo bad"))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void rejectsNonWhitelistedProgram() {
        assertThatThrownBy(() -> parser.parse("bash -lc ls"))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void keepsQuotedArgumentsTogether() {
        ParsedCommand command = parser.parse("git commit -m 'first commit'");

        assertThat(command.isGit()).isTrue();
        assertThat(command.subcommand()).isEqualTo("commit");
        assertThat(command.args()).containsExactly("-m", "first commit");
    }
}

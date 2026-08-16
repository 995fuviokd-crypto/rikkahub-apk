package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolApprovalTest {

    @Test
    fun `base tool names fall back to defaults`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
    }

    @Test
    fun `suffixed tool names resolve against base defaults`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file_2", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell_2", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell_3", emptyMap()))
    }

    @Test
    fun `exact override takes precedence over base override`() {
        val overrides = mapOf(
            "workspace_shell_2" to false,
            "workspace_shell" to true,
        )
        assertFalse(resolveWorkspaceToolApproval("workspace_shell_2", overrides))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell_3", overrides))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", overrides))
    }

    @Test
    fun `unknown tool names default to false`() {
        assertFalse(resolveWorkspaceToolApproval("unknown_tool", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_shell_2_3", emptyMap()))
    }
}

package com.undici77.aitoolset.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffBuilderTest {
    private fun d(path: String, body: String = "+x") =
        "diff --git a/$path b/$path\nindex 1..2 100644\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n$body\n"

    @Test fun twoFilesWithinBudget() {
        val r = DiffBuilder.build(d("a.c") + d("b.c"), emptyList(), 10_000)
        assertFalse(r.truncated)
        assertEquals("M a.c\nM b.c", r.files)
        assertTrue(r.diff.contains("a/a.c") && r.diff.contains("a/b.c"))
    }

    @Test fun overBudgetListsOmitted() {
        val r = DiffBuilder.build(d("a.c") + d("b.c") + d("c.c"), emptyList(), d("a.c").length + 5)
        assertTrue(r.truncated)
        assertTrue(r.diff, r.diff.contains("[diff omitted for 2 more files: b.c, c.c]"))
        assertEquals("M a.c\nM b.c\nM c.c", r.files)
    }

    @Test fun perFileCap() {
        val r = DiffBuilder.build(d("a.c", "+" + "x".repeat(500)), emptyList(), 10_000, perFileCap = 100)
        assertTrue(r.diff, r.diff.contains("… [truncated "))
    }

    @Test fun lockFileOmitted() {
        val r = DiffBuilder.build(d("Cargo.lock", "+secret-long-content"), emptyList(), 10_000)
        assertTrue(r.diff.contains("[content omitted]"))
        assertFalse(r.diff.contains("secret-long-content"))
    }

    @Test fun renameDetected() {
        val raw = "diff --git a/old.c b/new.c\nsimilarity index 90%\nrename from old.c\nrename to new.c\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("R old.c -> new.c", DiffBuilder.build(raw, emptyList(), 10_000).files)
    }

    @Test fun newAndDeleted() {
        val raw = "diff --git a/n.c b/n.c\nnew file mode 100644\n@@ -0,0 +1 @@\n+a\n" +
            "diff --git a/o.c b/o.c\ndeleted file mode 100644\n@@ -1 +0,0 @@\n-a\n"
        assertEquals("A n.c\nD o.c", DiffBuilder.build(raw, emptyList(), 10_000).files)
    }

    @Test fun untrackedBinarySkipped() {
        val r = DiffBuilder.build("", listOf("t.txt" to "hello", "b.bin" to "a\u0000b", "big.dat" to null), 10_000)
        assertTrue(r.diff.contains("new file (untracked): t.txt\nhello"))
        assertFalse(r.diff.contains("a\u0000b"))
        assertEquals("A t.txt\nA b.bin\nA big.dat", r.files)
    }
}

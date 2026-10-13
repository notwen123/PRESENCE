package com.presence

import com.presence.evidence.CheckpointData
import com.presence.evidence.EvidenceChain
import com.presence.evidence.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Golden vectors shared with backend/tests/test_attest.py::test_evidence_chain_golden_vector. */
class EvidenceChainTest {
    private fun cps() = (0 until 6).map { i ->
        CheckpointData(i, 1_700_000_000_000 + i * 10_000L, (i + 1) * 10_000L, true, 2, 700L + i * 100, 90 + i, 150 + i * 10)
    }

    @Test
    fun matchesServerImplementation() {
        val chain = EvidenceChain("session-1", "nonce-1")
        assertEquals("c52244702ffcfcac1b4198db2c0c1f086640a36441c9bd265217dec3fae9a5ef", chain.head.toHex())
        cps().forEach { chain.append(it) }
        assertEquals("b5312383492ebad14201269cbb6fbfa77c391156fe826ec187a083a10846b811", chain.hashes[0].toHex())
        assertEquals("189610d148640d4be13c7399bf39f83329d1b0d014e50e513aa2bfeddbeaaffb", chain.root().toHex())
    }

    @Test
    fun anyChangedCheckpointChangesTheRoot() {
        val honest = EvidenceChain("session-1", "nonce-1").apply { cps().forEach { append(it) } }
        val tampered = EvidenceChain("session-1", "nonce-1").apply {
            cps().forEachIndexed { i, cp -> append(if (i == 2) cp.copy(interactions = 9) else cp) }
        }
        assertNotEquals(honest.root().toHex(), tampered.root().toHex())
    }
}

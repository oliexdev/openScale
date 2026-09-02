/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [AfuB1Lib] — the wire protocol of the "AFU B1" scale.
 *
 * All byte fixtures were captured with the official AFU app (btsnoop HCI logs) while
 * reverse-engineering the protocol.
 */
class AfuB1LibTest {

    private fun hex(s: String) = s.replace(" ", "")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Independent CRC reimplementation — cross-checks the lib's checksum. */
    private fun expectedCrc(raw: ByteArray): Int {
        var sum = 0
        for (i in 2 until 19) sum += raw[i].toInt() and 0xFF
        return sum and 0x1F
    }

    /** Assembles a valid 20-byte AFU frame around the 16 middle bytes (index 2..17). */
    private fun frame(middle: ByteArray, wireType: Int): ByteArray {
        require(middle.size == 16) { "middle must be 16 bytes, got ${middle.size}" }
        val f = ByteArray(AfuB1Lib.FRAME_SIZE)
        f[0] = 0xAF.toByte(); f[1] = 0x45.toByte()
        middle.copyInto(f, 2)
        f[18] = wireType.toByte()
        f[19] = expectedCrc(f).toByte()
        return f
    }

    /** Blanks the timestamp slot (raw[3..6]) and the CRC covering it for comparisons. */
    private fun maskTime(f: ByteArray): ByteArray = f.copyOf().also {
        for (i in 3..6) it[i] = 0
        it[19] = 0
    }

    // --- Frame builders (phone -> scale) --------------------------------------

    @Test
    fun buildTimeSync_matchesCapture() {
        // af4516ba90806a20000000000000000000005f09
        assertThat(AfuB1Lib.buildTimeSync(1786810554L))
            .isEqualTo(hex("af4516ba90806a20000000000000000000005f09"))
    }

    @Test
    fun buildSyncRequest_first_matchesCapture() {
        // af45040001afb6171d010002b4c61b560100620f  (frame 1381 of the capture)
        assertThat(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
            .isEqualTo(hex("af45040001afb6171d010002b4c61b560100620f"))
    }

    @Test
    fun buildSyncRequest_second_matchesCapture() {
        // af45040103a5f8111d020004b4c61b1d0100620e
        assertThat(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
            .isEqualTo(hex("af45040103a5f8111d020004b4c61b1d0100620e"))
    }

    @Test
    fun buildSyncRequest_first_hasType62() {
        val f = AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST)
        assertThat(f.size).isEqualTo(20)
        assertThat(f[18].toInt() and 0xFF).isEqualTo(AfuB1Lib.WIRE_SYNC_REQUEST)
        assertThat(f[2].toInt() and 0xFF).isEqualTo(AfuB1Lib.CMD_SYNC_REQUEST)
    }

    @Test
    fun buildUserProfile_matchesCapture() {
        // af453fd490806a0001afb6171d01881303006107
        val f = AfuB1Lib.buildUserProfile(
            variant = AfuB1Lib.ProfileVariant.INITIAL,
            nowEpochSeconds = 1786810580L,
            user = 1,
            age = 29,
            sex = 1,
            targetKg = 50.0f
        )
        assertThat(f).isEqualTo(hex("af453fd490806a0001afb6171d01881303006107"))
    }

    @Test
    fun buildUserProfile_getWeighAgainVariants_matchCapture() {
        // Frames 3367 / 5604 / 7980 of btsnoop_hci_260817_140338.log. Only the
        // timestamp slot (raw[3..6]) is masked — it differs per capture moment.
        val cases = listOf(
            AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_1 to
                    "af454ba4826a200001afb6171d01881303006115",
            AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_2 to
                    "af455ba4826a200001afd9171d01881303006108",
            AfuB1Lib.ProfileVariant.FINAL to
                    "af4568a4826a200001afd9171d01881303006115",
        )
        for ((variant, captured) in cases) {
            val built = AfuB1Lib.buildUserProfile(
                variant = variant,
                nowEpochSeconds = 0L,
                user = 1,
                age = 29,
                sex = 1,
                targetKg = 50.0f
            )
            assertThat(maskTime(built)).isEqualTo(maskTime(hex(captured)))
        }
    }

    @Test
    fun buildHistoryDumpRequest_matchesCapture() {
        // af458e4090806a0001afde171d0188130300610a
        val f = AfuB1Lib.buildHistoryDumpRequest(
            nowEpochSeconds = 1786810432L,
            user = 1,
            age = 29,
            sex = 1,
            targetKg = 50.0f
        )
        assertThat(f).isEqualTo(hex("af458e4090806a0001afde171d0188130300610a"))
    }

    @Test
    fun buildChunkRequest_matchesCapture() {
        // af45140000000000000000000000000000005f13  (frame 3395 of the capture)
        assertThat(AfuB1Lib.buildChunkRequest())
            .isEqualTo(hex("af45140000000000000000000000000000005f13"))
    }

    @Test
    fun buildAck_matchesCaptures() {
        // af45125400050000000000000000000000005f0a  (ack 0x54, correlation=5)
        assertThat(AfuB1Lib.buildAck(AfuB1Lib.WIRE_STORED_RECORD, 5))
            .isEqualTo(hex("af45125400050000000000000000000000005f0a"))
        // af45125100000000000000000000000000005f02  (ack 0x51)
        assertThat(AfuB1Lib.buildAck(AfuB1Lib.WIRE_LIVE_WEIGHT, 0))
            .isEqualTo(hex("af45125100000000000000000000000000005f02"))
        // af45125200000000000000000000000000005f03  (ack 0x52)
        assertThat(AfuB1Lib.buildAck(AfuB1Lib.WIRE_IMPEDANCE_RESULT, 0))
            .isEqualTo(hex("af45125200000000000000000000000000005f03"))
    }

    @Test
    fun buildFrame_computesCrcCorrectly() {
        val f = AfuB1Lib.buildTimeSync(1786810554L)
        assertThat(f[19].toInt() and 0x1F).isEqualTo(expectedCrc(f))
    }

    // --- Frame decoder (scale -> phone) ---------------------------------------

    @Test
    fun parse_liveWeightFrame() {
        // af45b2ed080002800188384b0000000000005106
        // live 0x51: weight 60850 g (60.85 kg), mode 0x02, not locked
        val f = AfuB1Lib.parse(hex("af45b2ed080002800188384b0000000000005106"))
        assertThat(f).isInstanceOf(AfuB1Lib.ScaleFrame.LiveWeight::class.java)
        val live = f as AfuB1Lib.ScaleFrame.LiveWeight
        assertThat(live.weightGrams).isEqualTo(60850)
        assertThat(live.mode).isEqualTo(0x02)
        assertThat(live.locked).isFalse()
    }

    @Test
    fun parse_lockedLiveWeight_setsStateBit() {
        // Same live frame as above but with bit 31 of the flags word set (state/locked = 1).
        val vt = AfuB1Lib.convertIntEndian(0x8000EDB2.toInt()) // weight 60850 g + locked bit
        val middle = ByteArray(16)
        middle[0] = ((vt ushr 24) and 0xFF).toByte()
        middle[1] = ((vt ushr 16) and 0xFF).toByte()
        middle[2] = ((vt ushr 8) and 0xFF).toByte()
        middle[3] = (vt and 0xFF).toByte()
        middle[4] = 0x02 // mode

        val live = AfuB1Lib.parse(frame(middle, AfuB1Lib.WIRE_LIVE_WEIGHT))
        assertThat(live).isEqualTo(
            AfuB1Lib.ScaleFrame.LiveWeight(weightGrams = 60850, mode = 0x02, locked = true)
        )
    }

    @Test
    fun parse_historyFrame() {
        // af450046a4826a7aee0880000127020000005404
        // stored 0x54 record: time 1786946630, weight 61050 g (61.05 kg), resistance 551 Ohm
        val f = AfuB1Lib.parse(hex("af450046a4826a7aee0880000127020000005404"))
        assertThat(f).isInstanceOf(AfuB1Lib.ScaleFrame.StoredRecord::class.java)
        val h = f as AfuB1Lib.ScaleFrame.StoredRecord
        assertThat(h.historyType).isEqualTo(0)
        assertThat(h.sequence).isEqualTo(0)
        assertThat(h.timestampEpochSeconds).isEqualTo(1786946630L)
        assertThat(h.weightGrams).isEqualTo(61050)
        assertThat(h.resistanceOhms).isEqualTo(551)
        // seq=0 is the current LOCKED weight — the scale re-serves it until ACKed.
        assertThat(h.locked).isTrue()
    }

    @Test
    fun parse_historyFrame_encodedSeqInHeader() {
        // Header byte 0x14 = history_type 0 + history_seq 5 (bits 2..7).
        val middle = ByteArray(16)
        middle[0] = 0x14

        val h = AfuB1Lib.parse(frame(middle, AfuB1Lib.WIRE_STORED_RECORD))
        assertThat(h).isEqualTo(
            AfuB1Lib.ScaleFrame.StoredRecord(
                historyType = 0,
                sequence = 5,
                timestampEpochSeconds = 0L,
                weightGrams = 0,
                hasElectrode = false,
                hasHeartRate = false,
                hasPhaseAngle = false,
                hasZx = false,
                hasTemperature = false,
                locked = false,
                resistanceOhms = 0
            )
        )
    }

    @Test
    fun parse_impedanceFrame() {
        // af4501002702000000000000000000000000521c
        // 0x52 single-impedance result: valid=1, adc=551
        val f = AfuB1Lib.parse(hex("af4501002702000000000000000000000000521c"))
        assertThat(f).isInstanceOf(AfuB1Lib.ScaleFrame.ImpedanceResult::class.java)
        val i = f as AfuB1Lib.ScaleFrame.ImpedanceResult
        assertThat(i.valid).isTrue()
        assertThat(i.adcOhms).isEqualTo(551)
    }

    @Test
    fun parse_chunkFrame() {
        // af4514001058a2e6e8296fe2d7799037224c5f0a
        // 0x5f/0x14 body-comp chunk index 0, 14-byte payload
        val f = AfuB1Lib.parse(hex("af4514001058a2e6e8296fe2d7799037224c5f0a"))
        assertThat(f).isInstanceOf(AfuB1Lib.ScaleFrame.BodyCompChunk::class.java)
        val c = f as AfuB1Lib.ScaleFrame.BodyCompChunk
        assertThat(c.chunkIndex).isEqualTo(0)
        assertThat(c.payload.size).isEqualTo(14)
    }

    @Test
    fun parse_ackFrame() {
        // af45125f16000000000000000000000000005f06
        // 0x5f/0x12 ack: reply_cmd=0x5f
        val f = AfuB1Lib.parse(hex("af45125f16000000000000000000000000005f06"))
        assertThat(f).isInstanceOf(AfuB1Lib.ScaleFrame.Acknowledgement::class.java)
        val a = f as AfuB1Lib.ScaleFrame.Acknowledgement
        assertThat(a.replyCmd).isEqualTo(0x5F)
        assertThat(a.replyOp).isEqualTo(0x16)
    }

    @Test
    fun parse_phoneToScaleTypes_rejected() {
        // Type 0x61 (profile) / 0x62 (sync-request) never arrive from the scale.
        val f = AfuB1Lib.buildUserProfile(
            variant = AfuB1Lib.ProfileVariant.INITIAL,
            nowEpochSeconds = 1786810580L,
            user = 1,
            age = 29,
            sex = 1,
            targetKg = 50.0f
        )
        assertThat(AfuB1Lib.parse(f)).isNull()
        assertThat(AfuB1Lib.parse(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))).isNull()
    }

    // --- Robustness ------------------------------------------------------------

    @Test
    fun parse_wrongLength_rejected() {
        assertThat(AfuB1Lib.parse(byteArrayOf(0xAF.toByte()))).isNull()
        assertThat(AfuB1Lib.parse(ByteArray(19))).isNull()
        assertThat(AfuB1Lib.parse(ByteArray(21))).isNull()
    }

    @Test
    fun parse_badHeader_rejected() {
        val f = AfuB1Lib.buildTimeSync(1786810554L)
        f[0] = 0x00 // not 0xAF
        assertThat(AfuB1Lib.parse(f)).isNull()
    }

    @Test
    fun parse_badCrc_rejected() {
        val f = AfuB1Lib.buildTimeSync(1786810554L)
        f[19] = ((f[19].toInt() + 1) and 0xFF).toByte()
        assertThat(AfuB1Lib.parse(f)).isNull()
    }

    // --- Body composition from impedance ----------------------------------------

    @Test
    fun bodyFatPercent_referenceValues() {
        // Reference outputs of the python reference implementation:
        // male, 29y, 175cm, 61.0kg @ 550 Ohm -> 16.7 %; 61.1kg @ 545 Ohm -> 16.6 %
        assertThat(AfuB1Lib.bodyFatPercent(61.0, 175.0, 29, 1, 550.0)).isEqualTo(16.7f)
        assertThat(AfuB1Lib.bodyFatPercent(61.1, 175.0, 29, 1, 545.0)).isEqualTo(16.6f)
        assertThat(AfuB1Lib.bodyFatPercent(61.0, 175.0, 29, 1, 0.0)).isNull()
    }

    @Test
    fun bodyComposition_referenceValues() {
        val c = AfuB1Lib.bodyComposition(1, 29, 175f, 61f, 16.7f)!!
        assertThat(c.waterPercent).isWithin(1e-9).of(56.15439616656694)
        assertThat(c.skeletalMusclePercent).isWithin(1e-9).of(50.25818621525999)
        assertThat(c.boneMassKg.toDouble()).isWithin(1e-9).of(2.6422760486602783)
        assertThat(c.visceralFatIndex).isEqualTo(6)
        assertThat(c.proteinPercent).isWithin(1e-9).of(22.814002990722656)
        assertThat(c.fatFreeMassKg.toDouble()).isWithin(1e-9).of(50.8129997253418)

        val c2 = AfuB1Lib.bodyComposition(1, 29, 175f, 61.1f, 16.6f)!!
        assertThat(c2.waterPercent).isWithin(1e-9).of(56.27454136258639)
        assertThat(c2.skeletalMusclePercent).isWithin(1e-9).of(50.36572084232014)
        assertThat(c2.boneMassKg.toDouble()).isWithin(1e-9).of(2.649784803390503)
        assertThat(c2.visceralFatIndex).isEqualTo(5)
        assertThat(c2.proteinPercent).isWithin(1e-9).of(22.788658142089844)
        assertThat(c2.fatFreeMassKg.toDouble()).isWithin(1e-9).of(50.9573974609375)
    }

    @Test
    fun bodyComposition_rejectsOutOfRange() {
        assertThat(AfuB1Lib.bodyComposition(1, 29, 175f, 61f, 3.9f)).isNull()
        assertThat(AfuB1Lib.bodyComposition(1, 29, 175f, 61f, 75.5f)).isNull()
        assertThat(AfuB1Lib.bodyComposition(1, 9, 175f, 61f, 20f)).isNull()
        assertThat(AfuB1Lib.bodyComposition(1, 29, 80f, 61f, 20f)).isNull()
        assertThat(AfuB1Lib.bodyComposition(1, 29, 175f, 250f, 20f)).isNull()
    }

    @Test
    fun buildThenParse_roundtrip() {
        val f = AfuB1Lib.buildUserProfile(
            variant = AfuB1Lib.ProfileVariant.INITIAL,
            nowEpochSeconds = 1786810580L,
            user = 1,
            age = 29,
            sex = 1,
            targetKg = 50.0f
        )
        // A phone->scale frame is not something the scale would echo, but the CRC must
        // still be self-consistent so the scale can validate it.
        assertThat(f[19].toInt() and 0x1F).isEqualTo(expectedCrc(f))
    }
}

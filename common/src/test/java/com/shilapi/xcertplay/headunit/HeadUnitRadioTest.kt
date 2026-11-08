package com.shilapi.xcertplay.headunit

import com.shilapi.xcertplay.airplay.CarPlayRadio
import com.shilapi.xcertplay.airplay.CarRadioBand
import com.shilapi.xcertplay.airplay.CarRadioCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitRadioTest {
    private fun fyt() = FytRadio.State().apply {
        band = FytRadio.BAND_FM1
        frequency = 10_150 // 101.50 MHz in 10 kHz
        appId = FytRadio.APP_ID_RADIO
        ps = "TARMAC  "
        presets[FytRadio.BAND_FM1] = 10_150
        presets[FytRadio.BAND_FM1 + 1] = 9_910
        presets[FytRadio.BAND_FM1 + 2] = 0 // an empty preset
        presets[1] = 531 // an AM preset
        presetNames[FytRadio.BAND_FM1 + 1] = "DEMO"
    }

    @Test fun fytsTunerBecomesCarPlaysFmSource() {
        val reading = FytRadio.reading(fyt())!!
        assertEquals(CarRadioBand.FM, reading.band)
        assertEquals(101_500, reading.frequencyKhz)
        assertEquals(listOf("101.5", "DEMO"), reading.fmStations.map { it.name })
        assertEquals(CarPlayRadio.fmId(101_500), reading.fmCurrent)
        assertEquals("TARMAC", reading.title)
        assertTrue(reading.playing)
        assertNull("nothing said yet", FytRadio.reading(FytRadio.State()))
        assertEquals("AM 531", FytRadio.reading(fyt().apply { band = 0; frequency = 531 })!!.title)
    }

    @Test fun fytCommandsTuneThenMakeTheRadioTheCurrentApp() {
        val play = FytRadio.Command(FytModuleIpc.MODULE_MAIN, FytRadio.C_APP_ID, intArrayOf(FytRadio.APP_ID_RADIO))
        assertEquals(listOf(FytRadio.Command(FytRadio.MODULE_RADIO, FytRadio.C_SELECT_CHANNEL, intArrayOf(FytRadio.BAND_FM1 + 1)), play),
            FytRadio.commands(CarRadioCommand.Tune(CarRadioBand.FM, CarPlayRadio.fmId(99_100)), fyt()))
        assertEquals(listOf(FytRadio.Command(FytRadio.MODULE_RADIO, FytRadio.C_FREQ, intArrayOf(FytRadio.FREQ_DIRECT_NEW, 10_470)), play),
            FytRadio.commands(CarRadioCommand.TuneFrequency(104_700), fyt()))
        val onAm = fyt().apply { band = 0 }
        assertEquals(FytRadio.Command(FytRadio.MODULE_RADIO, FytRadio.C_BAND, intArrayOf(FytRadio.BAND_FM1)),
            FytRadio.commands(CarRadioCommand.TuneFrequency(104_700), onAm).first())
        assertEquals(listOf(FytRadio.Command(FytModuleIpc.MODULE_MAIN, FytRadio.C_APP_ID, intArrayOf(FytTrackText.APP_ID_THIRD_PLAYER))),
            FytRadio.commands(CarRadioCommand.Pause, fyt()))
        assertTrue("FYT has no DAB", FytRadio.commands(CarRadioCommand.Source(CarRadioBand.DAB), fyt()).isEmpty())
    }

    @Test fun teyesTunerBecomesCarPlaysFmSource() {
        val now = SpdRadio.FreqInfo("FM", 101_500)
        val list = listOf(SpdRadio.FreqInfo("FM", 99_100, "DEMO"), now, SpdRadio.FreqInfo("FM", 101_500))
        val reading = SpdRadio.reading(now, list, ps = "TARMAC", radiotext = " Song - Artist ", playState = 1)!!
        assertEquals(101_500, reading.frequencyKhz)
        assertEquals(listOf("DEMO", "101.5"), reading.fmStations.map { it.name })
        assertEquals(CarPlayRadio.fmId(101_500), reading.fmCurrent)
        assertEquals("TARMAC", reading.title)
        assertEquals("Song - Artist", reading.artist)
        assertTrue(reading.playing)
        assertEquals("AM 531", SpdRadio.reading(SpdRadio.FreqInfo("AM", 531), emptyList(), null, null, 0)!!.title)
        assertNull(SpdRadio.reading(null, emptyList(), null, null, null))
    }

    @Test fun teyesCommandsAlwaysCarryABandAndTuneInKhz() {
        val fm = SpdRadio.FreqInfo("FM", 101_500)
        assertEquals(listOf(SpdRadio.Call.Command(SpdRadio.CMD_SET_FREQ, 99_100, band = "FM")),
            SpdRadio.calls(CarRadioCommand.Tune(CarRadioBand.FM, CarPlayRadio.fmId(99_100)), fm))
        assertEquals(listOf(SpdRadio.Call.Command(SpdRadio.CMD_SET_BAND, band = "FM"), SpdRadio.Call.Command(SpdRadio.CMD_SET_FREQ, 104_700, band = "FM")),
            SpdRadio.calls(CarRadioCommand.TuneFrequency(104_700), SpdRadio.FreqInfo("AM", 531)))
        assertEquals(listOf(SpdRadio.Call.EnterSource(SpdRadio.SOURCE_TOKEN), SpdRadio.Call.Command(SpdRadio.CMD_PLAY_STATE, 1, band = "FM")),
            SpdRadio.calls(CarRadioCommand.Play, fm))
        assertEquals(listOf(SpdRadio.Call.Command(SpdRadio.CMD_PRESET_UP, band = "FM")), SpdRadio.calls(CarRadioCommand.Next, fm))
        assertTrue(SpdRadio.calls(CarRadioCommand.Tune(CarRadioBand.DAB, "dab-1"), fm).isEmpty())
    }
}

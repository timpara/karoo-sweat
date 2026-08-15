package de.timpara.karoosweat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychrometricsTest {

    @Test
    fun `saturation vapour pressure matches reference values`() {
        // Standard steam table values in kPa.
        assertEquals(0.6113, Psychrometrics.saturationVapourPressure(0.0), 0.005)
        assertEquals(1.2281, Psychrometrics.saturationVapourPressure(10.0), 0.01)
        assertEquals(2.3390, Psychrometrics.saturationVapourPressure(20.0), 0.02)
        assertEquals(4.2455, Psychrometrics.saturationVapourPressure(30.0), 0.03)
        assertEquals(7.3814, Psychrometrics.saturationVapourPressure(40.0), 0.06)
    }

    @Test
    fun `ambient vapour pressure scales with relative humidity`() {
        val sat = Psychrometrics.saturationVapourPressure(25.0)
        assertEquals(sat, Psychrometrics.ambientVapourPressure(25.0, 100.0), 1e-9)
        assertEquals(sat / 2, Psychrometrics.ambientVapourPressure(25.0, 50.0), 1e-9)
        assertEquals(0.0, Psychrometrics.ambientVapourPressure(25.0, 0.0), 1e-9)
    }

    @Test
    fun `humidity out of range is clamped`() {
        val sat = Psychrometrics.saturationVapourPressure(25.0)
        assertEquals(sat, Psychrometrics.ambientVapourPressure(25.0, 140.0), 1e-9)
        assertEquals(0.0, Psychrometrics.ambientVapourPressure(25.0, -20.0), 1e-9)
    }

    @Test
    fun `dew point equals air temperature at saturation`() {
        assertEquals(20.0, Psychrometrics.dewPointC(20.0, 100.0), 0.01)
    }

    @Test
    fun `dew point is below air temperature when unsaturated`() {
        assertTrue(Psychrometrics.dewPointC(25.0, 50.0) < 25.0)
    }
}

package com.example.domain

import java.util.Locale
import kotlin.math.abs

enum class MeasureCategory(val label: String) {
    LENGTH("Length"),
    AREA("Area"),
    VOLUME("Volume"),
    MASS("Mass"),
    ANGLE("Angle"),
    TEMPERATURE("Temp"),
    SPEED("Speed"),
    PRESSURE("Pressure"),
    DIGITAL("Data")
}

data class MeasureUnit(
    val id: String,
    val name: String,
    val symbol: String,
    val category: MeasureCategory,
    /** Factor to convert 1 unit into the category's base unit (ignored for Temperature). */
    val toBaseFactor: Double
)

object UnitConverterEngine {

    val allUnits: List<MeasureUnit> = listOf(
        // LENGTH (base: meter)
        MeasureUnit("mm", "Millimeter", "mm", MeasureCategory.LENGTH, 0.001),
        MeasureUnit("cm", "Centimeter", "cm", MeasureCategory.LENGTH, 0.01),
        MeasureUnit("m", "Meter", "m", MeasureCategory.LENGTH, 1.0),
        MeasureUnit("km", "Kilometer", "km", MeasureCategory.LENGTH, 1000.0),
        MeasureUnit("in", "Inch", "in", MeasureCategory.LENGTH, 0.0254),
        MeasureUnit("ft", "Foot", "ft", MeasureCategory.LENGTH, 0.3048),
        MeasureUnit("yd", "Yard", "yd", MeasureCategory.LENGTH, 0.9144),
        MeasureUnit("mi", "Mile", "mi", MeasureCategory.LENGTH, 1609.344),
        MeasureUnit("nmi", "Nautical Mile", "nmi", MeasureCategory.LENGTH, 1852.0),
        MeasureUnit("um", "Micrometer", "µm", MeasureCategory.LENGTH, 1e-6),

        // AREA (base: square meter)
        MeasureUnit("mm2", "Sq Millimeter", "mm²", MeasureCategory.AREA, 1e-6),
        MeasureUnit("cm2", "Sq Centimeter", "cm²", MeasureCategory.AREA, 1e-4),
        MeasureUnit("m2", "Sq Meter", "m²", MeasureCategory.AREA, 1.0),
        MeasureUnit("ha", "Hectare", "ha", MeasureCategory.AREA, 10_000.0),
        MeasureUnit("km2", "Sq Kilometer", "km²", MeasureCategory.AREA, 1_000_000.0),
        MeasureUnit("in2", "Sq Inch", "in²", MeasureCategory.AREA, 0.00064516),
        MeasureUnit("ft2", "Sq Foot", "ft²", MeasureCategory.AREA, 0.09290304),
        MeasureUnit("yd2", "Sq Yard", "yd²", MeasureCategory.AREA, 0.83612736),
        MeasureUnit("acre", "Acre", "ac", MeasureCategory.AREA, 4046.8564224),

        // VOLUME (base: liter)
        MeasureUnit("ml", "Milliliter", "mL", MeasureCategory.VOLUME, 0.001),
        MeasureUnit("l", "Liter", "L", MeasureCategory.VOLUME, 1.0),
        MeasureUnit("m3", "Cubic Meter", "m³", MeasureCategory.VOLUME, 1000.0),
        MeasureUnit("tsp", "Teaspoon (US)", "tsp", MeasureCategory.VOLUME, 0.00492892159375),
        MeasureUnit("tbsp", "Tablespoon (US)", "tbsp", MeasureCategory.VOLUME, 0.01478676478125),
        MeasureUnit("floz", "Fluid Ounce (US)", "fl oz", MeasureCategory.VOLUME, 0.0295735295625),
        MeasureUnit("cup", "Cup (US)", "cup", MeasureCategory.VOLUME, 0.2365882365),
        MeasureUnit("pt", "Pint (US)", "pt", MeasureCategory.VOLUME, 0.473176473),
        MeasureUnit("qt", "Quart (US)", "qt", MeasureCategory.VOLUME, 0.946352946),
        MeasureUnit("gal", "Gallon (US)", "gal", MeasureCategory.VOLUME, 3.785411784),

        // MASS (base: kilogram)
        MeasureUnit("mg", "Milligram", "mg", MeasureCategory.MASS, 1e-6),
        MeasureUnit("g", "Gram", "g", MeasureCategory.MASS, 0.001),
        MeasureUnit("kg", "Kilogram", "kg", MeasureCategory.MASS, 1.0),
        MeasureUnit("t", "Metric Tonne", "t", MeasureCategory.MASS, 1000.0),
        MeasureUnit("oz", "Ounce", "oz", MeasureCategory.MASS, 0.028349523125),
        MeasureUnit("lb", "Pound", "lb", MeasureCategory.MASS, 0.45359237),
        MeasureUnit("st", "Stone", "st", MeasureCategory.MASS, 6.35029318),

        // ANGLE (base: degree)
        MeasureUnit("deg", "Degree", "°", MeasureCategory.ANGLE, 1.0),
        MeasureUnit("rad", "Radian", "rad", MeasureCategory.ANGLE, 180.0 / Math.PI),
        MeasureUnit("grad", "Gradian", "gon", MeasureCategory.ANGLE, 0.9),
        MeasureUnit("arcmin", "Arcminute", "′", MeasureCategory.ANGLE, 1.0 / 60.0),
        MeasureUnit("arcsec", "Arcsecond", "″", MeasureCategory.ANGLE, 1.0 / 3600.0),
        MeasureUnit("turn", "Full Turn", "turn", MeasureCategory.ANGLE, 360.0),

        // TEMPERATURE (custom formula)
        MeasureUnit("c", "Celsius", "°C", MeasureCategory.TEMPERATURE, 1.0),
        MeasureUnit("f", "Fahrenheit", "°F", MeasureCategory.TEMPERATURE, 1.0),
        MeasureUnit("k", "Kelvin", "K", MeasureCategory.TEMPERATURE, 1.0),

        // SPEED (base: m/s)
        MeasureUnit("mps", "Meters/sec", "m/s", MeasureCategory.SPEED, 1.0),
        MeasureUnit("kmh", "Kilometers/hr", "km/h", MeasureCategory.SPEED, 1.0 / 3.6),
        MeasureUnit("mph", "Miles/hr", "mph", MeasureCategory.SPEED, 0.44704),
        MeasureUnit("kn", "Knot", "kn", MeasureCategory.SPEED, 0.5144444444),
        MeasureUnit("fps", "Feet/sec", "ft/s", MeasureCategory.SPEED, 0.3048),

        // PRESSURE (base: Pascal)
        MeasureUnit("pa", "Pascal", "Pa", MeasureCategory.PRESSURE, 1.0),
        MeasureUnit("kpa", "Kilopascal", "kPa", MeasureCategory.PRESSURE, 1000.0),
        MeasureUnit("bar", "Bar", "bar", MeasureCategory.PRESSURE, 100_000.0),
        MeasureUnit("psi", "PSI", "psi", MeasureCategory.PRESSURE, 6894.757293168),
        MeasureUnit("atm", "Atmosphere", "atm", MeasureCategory.PRESSURE, 101_325.0),
        MeasureUnit("mmhg", "mmHg (Torr)", "mmHg", MeasureCategory.PRESSURE, 133.322387415),

        // DIGITAL STORAGE (base: byte, IEC/decimal clarity using 1024 binary standard)
        MeasureUnit("b", "Byte", "B", MeasureCategory.DIGITAL, 1.0),
        MeasureUnit("kb", "Kilobyte", "KB", MeasureCategory.DIGITAL, 1024.0),
        MeasureUnit("mb", "Megabyte", "MB", MeasureCategory.DIGITAL, 1024.0 * 1024.0),
        MeasureUnit("gb", "Gigabyte", "GB", MeasureCategory.DIGITAL, 1024.0 * 1024.0 * 1024.0),
        MeasureUnit("tb", "Terabyte", "TB", MeasureCategory.DIGITAL, 1024.0 * 1024.0 * 1024.0 * 1024.0)
    )

    fun unitsFor(category: MeasureCategory): List<MeasureUnit> =
        allUnits.filter { it.category == category }

    fun findById(id: String): MeasureUnit? = allUnits.firstOrNull { it.id == id }

    fun convert(value: Double, from: MeasureUnit, to: MeasureUnit): Double {
        if (from.id == to.id) return value
        if (from.category != to.category) return value
        if (from.category == MeasureCategory.TEMPERATURE) {
            val celsius = when (from.id) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> value
            }
            return when (to.id) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                "k" -> celsius + 273.15
                else -> celsius
            }
        }
        val baseValue = value * from.toBaseFactor
        return baseValue / to.toBaseFactor
    }

    fun formatValue(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val absVal = abs(value)
        if (absVal > 0.0 && (absVal >= 1e9 || absVal < 1e-5)) {
            return String.format(Locale.US, "%.${decimals}e", value)
        }
        val formatted = String.format(Locale.US, "%.${decimals}f", value)
        return if (formatted.contains('.')) {
            formatted.trimEnd('0').trimEnd('.')
        } else {
            formatted
        }
    }
}

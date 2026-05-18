package com.automation.voiceassistant

import com.automation.voiceassistant.service.TtsTextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextCleanerTest {

    private fun clean(input: String) = TtsTextCleaner.clean(input)

    // ── Palabras sueltas siempre se conservan ────────────────────────

    @Test fun `texto plano sin cambios`() {
        val input = "Hola, esto es una prueba normal."
        assertEquals(input, clean(input))
    }

    @Test fun `letras con acento se conservan`() {
        val input = "áéíóúÁÉÍÓÚñÑ"
        assertEquals(input, clean(input))
    }

    @Test fun `digitos se conservan`() {
        val input = "El resultado es 42 y también 3.14"
        assertEquals(input, clean(input))
    }

    @Test fun `puntuacion de ritmo se conserva`() {
        // . , ; : ! ? ' …  deben sobrevivir
        val input = "Hola, ¿cómo estás? ¡Bien, gracias! Es decir: todo correcto; en serio…"
        assertEquals(input, clean(input))
    }

    // ── Code blocks ───────────────────────────────────────────────────

    @Test fun `bloque de codigo eliminado completo`() {
        val input = "Resultado:\n```\nprint('hola')\n```\nFin."
        val result = clean(input)
        assertFalse("El contenido del bloque no debe aparecer", result.contains("print"))
        assertTrue("El texto fuera del bloque debe quedar", result.contains("Resultado"))
        assertTrue("El texto fuera del bloque debe quedar", result.contains("Fin"))
    }

    @Test fun `codigo inline conserva la palabra interior`() {
        // `palabra` → "palabra"  (quitar backticks, conservar texto)
        val result = clean("Usa `config` para configurarlo.")
        assertTrue("La palabra 'config' debe conservarse", result.contains("config"))
        assertFalse("Los backticks no deben permanecer", result.contains("`"))
    }

    @Test fun `codigo inline varias palabras conserva todo`() {
        val result = clean("El valor `nombre del campo` es importante.")
        assertTrue(result.contains("nombre del campo"))
        assertFalse(result.contains("`"))
    }

    @Test fun `backtick suelto eliminado`() {
        val result = clean("texto con ` backtick suelto")
        assertFalse(result.contains("`"))
        assertTrue(result.contains("texto con"))
        assertTrue(result.contains("backtick suelto"))
    }

    // ── Negrita / cursiva ────────────────────────────────────────────

    @Test fun `negrita doble asterisco conserva palabras`() {
        val result = clean("Esto es **muy importante** para ti.")
        assertTrue(result.contains("muy importante"))
        assertFalse(result.contains("**"))
    }

    @Test fun `negrita triple asterisco conserva palabras`() {
        val result = clean("***advertencia*** crítica")
        assertTrue(result.contains("advertencia"))
        assertFalse(result.contains("*"))
    }

    @Test fun `cursiva simple asterisco conserva palabras`() {
        val result = clean("Es *bastante* probable.")
        assertTrue(result.contains("bastante"))
    }

    @Test fun `cursiva guion bajo conserva palabras`() {
        val result = clean("Para _énfasis_ usa guion bajo.")
        assertTrue(result.contains("énfasis"))
        assertFalse(result.contains("_"))
    }

    @Test fun `negrita guion bajo doble conserva palabras`() {
        val result = clean("__título importante__")
        assertTrue(result.contains("título importante"))
        assertFalse(result.contains("_"))
    }

    // ── Encabezados ───────────────────────────────────────────────────

    @Test fun `encabezado h1 conserva texto`() {
        val result = clean("# Introducción")
        assertTrue(result.contains("Introducción"))
        assertFalse(result.startsWith("#"))
    }

    @Test fun `encabezado h3 conserva texto`() {
        val result = clean("### Detalles técnicos")
        assertTrue(result.contains("Detalles técnicos"))
        assertFalse(result.contains("#"))
    }

    // ── Listas ───────────────────────────────────────────────────────

    @Test fun `lista con guion conserva texto`() {
        val input = "- primer elemento\n- segundo elemento"
        val result = clean(input)
        assertTrue(result.contains("primer elemento"))
        assertTrue(result.contains("segundo elemento"))
    }

    @Test fun `lista con asterisco conserva texto`() {
        val input = "* opción A\n* opción B"
        val result = clean(input)
        assertTrue(result.contains("opción A"))
        assertTrue(result.contains("opción B"))
    }

    // ── Líneas horizontales ──────────────────────────────────────────

    @Test fun `linea horizontal eliminada`() {
        val input = "Antes\n---\nDespués"
        val result = clean(input)
        assertTrue(result.contains("Antes"))
        assertTrue(result.contains("Después"))
        assertFalse(result.contains("---"))
    }

    // ── URLs ─────────────────────────────────────────────────────────

    @Test fun `url https eliminada`() {
        val result = clean("Visita https://www.ejemplo.com para más info.")
        assertFalse(result.contains("https"))
        assertFalse(result.contains("ejemplo.com"))
        assertTrue(result.contains("Visita"))
        assertTrue(result.contains("para más info"))
    }

    @Test fun `url http eliminada`() {
        val result = clean("Ver http://api.server.local/datos")
        assertFalse(result.contains("http"))
    }

    // ── Símbolos individuales ────────────────────────────────────────

    @Test fun `simbolo hash eliminado`() {
        assertFalse(clean("texto #etiqueta fin").contains("#"))
    }

    @Test fun `simbolo tilde eliminado`() {
        assertFalse(clean("texto ~tilde fin").contains("~"))
    }

    @Test fun `corchetes eliminados palabras conservadas`() {
        val result = clean("[enlace texto]")
        assertFalse(result.contains("["))
        assertFalse(result.contains("]"))
        assertTrue(result.contains("enlace texto"))
    }

    @Test fun `llaves eliminadas palabras conservadas`() {
        val result = clean("{clave: valor}")
        assertFalse(result.contains("{"))
        assertFalse(result.contains("}"))
        assertTrue(result.contains("clave"))
        assertTrue(result.contains("valor"))
    }

    @Test fun `parentesis eliminados palabras conservadas`() {
        val result = clean("función(argumento)")
        assertFalse(result.contains("("))
        assertFalse(result.contains(")"))
        assertTrue(result.contains("función"))
        assertTrue(result.contains("argumento"))
    }

    @Test fun `arroba eliminada`() {
        assertFalse(clean("usuario@dominio").contains("@"))
    }

    @Test fun `simbolos matematicos eliminados`() {
        val result = clean("a + b = c & d > e < f")
        assertFalse(result.contains("+"))
        assertFalse(result.contains("="))
        assertFalse(result.contains("&"))
        assertFalse(result.contains(">"))
        assertFalse(result.contains("<"))
        assertTrue(result.contains("a"))
        assertTrue(result.contains("b"))
    }

    @Test fun `barra vertical eliminada`() {
        val result = clean("columna1 | columna2")
        assertFalse(result.contains("|"))
        assertTrue(result.contains("columna1"))
        assertTrue(result.contains("columna2"))
    }

    // ── Espacios en blanco ───────────────────────────────────────────

    @Test fun `multiples espacios normalizados a uno`() {
        val result = clean("hola    mundo")
        assertEquals("hola mundo", result)
    }

    @Test fun `saltos de linea normalizados a espacio`() {
        val result = clean("línea uno\nlínea dos\nlínea tres")
        assertFalse(result.contains("\n"))
        assertTrue(result.contains("línea uno"))
        assertTrue(result.contains("línea dos"))
    }

    @Test fun `espacios al inicio y fin eliminados`() {
        assertEquals("hola", clean("  hola  "))
    }

    // ── Caso realista: respuesta de IA con markdown ──────────────────

    @Test fun `respuesta real de ia conserva todas las palabras clave`() {
        val respuesta = """
            ## Resumen
            El sistema **funciona correctamente**. Los pasos son:
            - Inicia el servicio con `start`
            - Verifica el estado con `status`
            
            Para más detalles visita https://docs.example.com/guide.

            > El tiempo estimado es _aproximadamente_ 5 minutos.
        """.trimIndent()

        val result = clean(respuesta)

        // Palabras importantes conservadas
        assertTrue(result.contains("Resumen"))
        assertTrue(result.contains("funciona correctamente"))
        assertTrue(result.contains("Inicia el servicio con"))
        assertTrue(result.contains("start"))
        assertTrue(result.contains("Verifica el estado con"))
        assertTrue(result.contains("status"))
        assertTrue(result.contains("Para más detalles visita"))
        assertTrue(result.contains("El tiempo estimado es"))
        assertTrue(result.contains("aproximadamente"))
        assertTrue(result.contains("5 minutos"))

        // Símbolos eliminados
        assertFalse(result.contains("#"))
        assertFalse(result.contains("**"))
        assertFalse(result.contains("`"))
        assertFalse(result.contains("https"))
        assertFalse(result.contains("_"))
        assertFalse(result.contains(">"))
        assertFalse(result.contains("-"))

        // Sin saltos de línea
        assertFalse(result.contains("\n"))
    }

    // ── Casos límite ─────────────────────────────────────────────────

    @Test fun `cadena vacia devuelve cadena vacia`() {
        assertEquals("", clean(""))
    }

    @Test fun `solo simbolos devuelve cadena vacia`() {
        assertEquals("", clean("# ** __ ## `````` ` ~ [] {} | \\ @ \$ % ^ & + = <> () \""))
    }

    @Test fun `texto sin ningun markdown no cambia`() {
        val input = "El asistente está listo. ¿Necesitas ayuda?"
        assertEquals(input, clean(input))
    }
}


package br.com.anderson.techrace

import java.util.Locale

/** Snapshot of EEPROM 0x01..0x19. Display only, never written to the module. */
class ModuleSettings(bytes: ByteArray) {
    val raw = bytes.copyOf()
    init { require(raw.size == 25) }
    private fun u(i: Int) = raw[i].toInt() and 255
    private fun word(i: Int) = u(i) + 256 * u(i + 1)
    private fun pct(i: Int) = fmt(u(i) * 100.0 / 64.0)
    private fun temp(i: Int) = TechRaceDecoder.temperatureC(u(i))?.let { "$it °C" } ?: "indisponível (ADC ${u(i)})"
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun bit(mask: Int) = if (u(0) and mask != 0) "1" else "0"
    val mapFlag get() = u(0) and 1 != 0
    val rpmFlag get() = u(0) and 2 != 0

    fun describe(rpmFactor: Double): String = """
        CONFIGURAÇÕES LIDAS — SOMENTE CONSULTA
        Interpretação conforme Principal.cpp; validar correspondência com o firmware conectado.

        Limite de correção: ${pct(3)} %
        RPM de referência: ${if (word(4) == 0) "indisponível" else fmt(60_000_000.0 / (word(4) * 3.2 * rpmFactor))}
        Partida a frio: ${fmt(word(12) * 0.82)} ms
        Limiar de mistura para partida/delta: ${pct(14)} %
        Temperatura de liberação da correção: ${temp(15)}
        Temperatura limite de injeção extra: ${temp(16)}
        Temperatura limite de partida a frio: ${temp(17)}
        Variação inicial do bico (delta): ${fmt(word(18) * 0.8)} µs
        Correção de aceleração (escala do EXE): ${pct(20)} %
        Injeção extra a frio: ${pct(21)} %
        Referência elétrica da sonda: ${u(22) * 5000 / 255} mV
        Tempo de ajuste (escala do EXE): ${(u(24) * 0.21).toInt()} — unidade não consta nos fontes

        FLAGS — valor 1 = bit presente
        0x01 MAP: ${bit(1)} (EXE: indicador MAP; planilha: MAP_ON)
        0x02 RPM: ${bit(2)} (EXE: indicador RPM; planilha: AUTO_ON)
        0x04: ${bit(4)} (EXE: partida_en; planilha: MODO_MAN)
        0x08: ${bit(8)} (EXE: lenta_en; planilha: MODO_BI)
        0x10: ${bit(16)} (EXE: sonda_en; planilha: SONDA_FREEZE)
        0x20 banda larga: ${bit(32)}
        0x40 aceleração rápida: ${bit(64)}
        0x80 enriquecimento a frio: ${bit(128)}
        A flag de banda larga não fornece a curva tensão/AFR do controlador.

        FAIXAS DE EDIÇÃO DO EXE — referência, não recomendação
        Correção: 10–60 %; delta inicial: 200–15000 µs.
        Extra aceleração: 0–30 %; extra frio: 0–10 %.
        Partida: 0–4000 ms; limite térmico partida: 15–35 °C.
        Limite térmico extra: 35–60 °C; libera correção: 0–100 °C.
        Ajuste: 10–50 (unidade não declarada); RPM pelos botões: 600–1500.
        O EXE troca o rótulo de sonda para V ao selecionar banda larga,
        mas não muda a conversão: aqui a referência permanece em mV.

        CAMPOS ADICIONAIS — valores brutos, sem escala presumida
        SETUP_FLAGS2: ${u(1)}
        MIX: ${u(2)}
        MAP_FATOR: ${u(6)}
        MAP lenta / meia / carga: ${u(7)} / ${u(8)} / ${u(9)}
        BICO_LENTA: ${word(10)}
        LAMBDA_RANGE: ${u(23)}
        LAMBDA_WAIT bruto: ${u(24)}

        EEPROM 0x01..0x19 (hex):
        ${TechRaceProtocol.toHex(raw)}
    """.trimIndent()
}

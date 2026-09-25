# TechRace: análise dos arquivos recebidos — 14/09/2026

## Identificação de versão

Porta_serial.cpp, button_OKClick, executa Send_command(0,3,1,1,dtemp).
A janela Versão é construída com dtemp[0] + ponto + dtemp[1].
Assim, o 2.0 relatado pelo usuário é a identificação devolvida pelo módulo,
independente do número de versão do APK (agora 2.4.1).
Não há captura serial do módulo nesta entrega. A planilha intitulada FLEX V10.0
não demonstra por si só que todos os endereços correspondem ao firmware 2.0.

Os dois C++ reenviados são idênticos byte a byte aos anteriores. A planilha de
comunicação complementa a interpretação: cabeçalho de resposta e função 13.

## Transporte e quadros

- 19200 baud, 8N1; CRC-8 polinômio 07, inicial 00, MSB-first, XOR final 00.
- Pedido: F3, função, endereço high, endereço low, quantidade, dados, CRC.
- Até 64 dados, conforme planilha. Mesmo consultas levam dados auxiliares no EXE.
- O APK inicializa esses auxiliares com zero. No EXE o buffer pode conter dados
  anteriores; na consulta de versão, o buffer local não é inicializado.
- Resposta: endereço do módulo, função, dados, CRC. A função é agora verificada.
- Há divergência de endereços: planilha cita 15/16 hex para ignição/injeção;
  o EXE ignora seu argumento device e transmite F3. Preservado F3 do EXE.
- O formato tem semelhança com Modbus, mas não se deve usar um driver Modbus RTU
  padrão: aqui o CRC é de 8 bits, diferente do CRC-16 Modbus.
- A planilha descreve término por silêncio de 1,5 byte; o EXE usa timeout
  entre bytes de 20 ms. O APK acumula fragmentos e usa 40 ms de espera de leitura.
  A agregação do USB e os tempos precisam de validação no equipamento real.

| Consulta | Função | Endereço | Quantidade enviada | Dados esperados na resposta |
|---|---:|---:|---:|---:|
| Telemetria | 0 | 0043 | 10 | 10 |
| EEPROM | 1 | 0001 | 25 | 25 |
| Versão | 3 | 0001 | 1 | 2, conforme uso no EXE |
| RAM adicional | 0 | 004D | 10 | 10; mapa ainda a validar |

Quadro de versão com auxiliar zerado: `F3 03 00 01 01 00 9C`.
A resposta 2.0 é interpretada como dados numéricos 02 00. Não é texto ASCII
"V2.0". Os testes usam respostas sintéticas e não provam o comportamento físico.

## Telemetria confirmada no monitor do EXE

| RAM | Campo | Conversão |
|---|---|---|
| 43–44 | Período RPM, little-endian | 60000000 / (raw × 3,2 × fator local RPM) |
| 45–46 | Tempo capturado do bico | raw × 0,0008 ms |
| 47 | INTERVALO | Valor bruto; escala não demonstrada |
| 48 | MIX_FINAL | raw × 100 / 64; divisão inteira no monitor |
| 49 | Y_PERCENT | Índice interno de mistura; não é teor de etanol medido |
| 4A | Temperatura | Curva NTC do EXE, R0=1100 Ω, T0=20,5 °C, beta=3000, pull-up=1000 Ω, ADC/256 |
| 4B | MAP | raw × 2,5 / 255 V |
| 4C | Sonda | raw × 5000 / 255 mV; divisão inteira no EXE |

O tempo do bico é identificado como CAPTURADO. Não há base suficiente para
rotulá-lo como tempo final aplicado depois de todos os acréscimos do módulo.
O MAP não é convertido em pressão sem uma curva específica.
A sonda não é convertida em AFR/lambda sem a curva do controlador.

## EEPROM 01–19 hexadecimal

O primeiro byte do retorno corresponde ao endereço 01, não ao endereço 00.
A coluna índice a seguir é a posição dentro dos 25 bytes recebidos.

| Endereço | Índice | Campo | Tratamento |
|---|---:|---|---|
| 01 | 0 | SETUP_FLAGS | Bits e divergências abaixo |
| 02 | 1 | SETUP_FLAGS2 | Bruto |
| 03 | 2 | MIX | Bruto |
| 04 | 3 | FINAL | ×100/64 %, ponto flutuante na tela de configuração |
| 05–06 | 4–5 | RPM da lenta | 60000000/(raw×3,2×fator) |
| 07 | 6 | MAP_FATOR | Bruto |
| 08–0A | 7–9 | MAP lenta/meia/carga | Brutos |
| 0B–0C | 10–11 | BICO_LENTA | Word bruto |
| 0D–0E | 12–13 | Tempo de partida a frio a 10 °C | ×0,82 ms (820 µs na planilha) |
| 0F | 14 | Mistura de entrada de partida/delta | ×100/64 % |
| 10 | 15 | QUENTE, libera correção | Curva NTC |
| 11 | 16 | MORNO, termina extra por frio | Curva NTC |
| 12 | 17 | FRIO, limite partida a frio | Curva NTC |
| 13–14 | 18–19 | DELTA_START | ×0,8 µs |
| 15 | 20 | DELTA_ALFA | ×100/64 % como EXE; descrição da planilha diverge |
| 16 | 21 | EXTRA_FRIO | ×100/64 % |
| 17 | 22 | LAMBDA_ESTEQ | ×5000/255 mV |
| 18 | 23 | LAMBDA_RANGE | Bruto |
| 19 | 24 | LAMBDA_WAIT | EXE exibe int(raw×0,21); unidade ausente |

Exemplo: 400 µs de variação inicial = 500 unidades = word 01F4 hex,
armazenado F4 01 nos índices 18–19. Isso descreve o limiar da variação do pulso,
não uma duração fixa a somar indiscriminadamente a todos os pulsos.

## Divergências de flags

| Máscara | Nome/uso no EXE | Nome na planilha |
|---|---|---|
| 01 | Indicador MAP | MAP_ON |
| 02 | Indicador RPM | AUTO_ON |
| 04 | partida_en | MODO_MAN |
| 08 | lenta_en | MODO_BI |
| 10 | sonda_en | SONDA_FREEZE |
| 20 | banda_larga | SONDA_WIDE |
| 40 | acel_rapida_en | EXTRA_INJECT |
| 80 | heat_en | EXTRA_COLD |

O app mostra os bits e ambos os nomes quando necessário. Nenhum indicador é
tratado como prova de que uma calibração foi executada corretamente.

## Limites de edição encontrados no EXE

Estes são limites da interface original, não recomendações de regulagem para o motor.
Não foram aplicados como cortes na leitura: um valor fora deles continua visível.

| Parâmetro | Faixa aceita pelo EXE |
|---|---|
| Limite de correção | 10–60 % |
| Mistura de entrada de partida/delta | 10 % até o limite de correção |
| Temperatura que libera correção | 0–100 °C |
| Variação inicial do bico | 200–15000 µs |
| Correção de aceleração | 0–30 % na escala da interface |
| Injeção extra a frio | 0–10 % |
| Temperatura limite de extra a frio | 35–60 °C |
| Tempo da partida a frio | 0–4000 ms |
| Temperatura limite da partida a frio | 15–35 °C |
| Tempo de ajuste | 10–50, unidade não explicitada |
| Referência da sonda | 0–1000 na escala numérica do EXE |
| Botões de ajuste de RPM | 600–1500 rpm, passos de 25 |

Outra inconsistência: banda_largaClick altera o rótulo para V, mas as rotinas
lerClick/gravarClick continuam usando a escala 5000/255 e o validador 0–1000.
Por isso a V2.4 mostra a referência sempre em mV e não copia a troca de unidade
isolada. O bit banda larga não basta para converter em AFR.

## O que sabemos sobre gravação e aprendizado

Principal.cpp confirma gravação EEPROM com função 4, endereço 1, 25 bytes,
preservando no buffer campos que a interface não altera e relendo após gravar.
Os limites operacionais de cada campo e correspondência de firmware não estão
completamente definidos. A V2.4 ainda não expõe edição irrestrita dos 25 bytes por segurança de compatibilidade.

`Program.cpp` resolveu o quadro de aprendizado que faltava. O software chama
`Send_command(0,13,1,1,temp)`: função/comando 13, endereço 1, um byte de dados.
Os seletores confirmados pelo próprio fonte são: 00 encerra/reset, 01 inicia
“Programando Sonda / RPM” e 02 inicia “Programando Sensor MAP”. Assim, usando o
mesmo enquadramento de `Porta_serial.cpp`, os quadros são:
- Sonda/RPM: `F3 0D 00 01 01 01 C9`;
- MAP: `F3 0D 00 01 01 02 C0`;
- encerrar/reset: `F3 0D 00 01 01 00 CE`.

Após os seletores 1 e 2, `Program.cpp` abre `FlexProgramForm` e habilita seu Timer1.
Como `FlexProgram.cpp` não foi fornecido, ainda não conhecemos a sequência de
mensagens/condições executada por esse formulário. A V2.4 não inventa essa etapa:
envia o comando comprovado, valida o ACK pelo CRC, acompanha os bits 0x02/0x01
da EEPROM e fornece encerramento explícito com seletor 0.

A planilha é compartilhada com outras aplicações e enumera mapas, ignição fixa,
saídas, DTCs e bootloader. Sua presença não demonstra que o FLEX 2.0 implemente
essas funções. Não foram criados botões que fingem essas capacidades.
No bootloader, a planilha descreve checksum por complemento de dois da soma,
diferente do CRC normal. Atualização de firmware não foi implementada.

## Entrega V2.4.1 e validação pendente

Implementado: consulta automática da versão ao conectar, consulta manual dos 25
bytes de EEPROM, exibição das escalas e flags, consulta experimental de 4D–56,
exportação TXT/CSV, monitor/demonstração e programação real dos seletores 0/1/2
da função 13 para Sonda/RPM e MAP, com pausa da telemetria e acompanhamento de status.

O histórico CSV é mantido em memória até exportar/limpar/encerrar o processo;
não é um gravador permanente em segundo plano. A exportação abre o seletor Android,
o que pausa a conexão USB por regra de ciclo de vida. Reconecte ao retornar.
Se uma consulta adicional falhar, o monitor é pausado e o diagnóstico mantém TX/RX.
Não é necessário apagar ou alterar o módulo para continuar testando.

Verificações locais: integridade dos fontes recebidos, cruzamento de endereços e
fórmulas, quatro vetores CRC conferidos executando a rotina original C++ e integridade do ZIP.
Testes Kotlin/JUnit incluídos para versão com dois bytes, EEPROM, CRC, função
incompatível, comprimentos inválidos e conversão de 400 µs. Não foram executados
localmente: não há Gradle/SDK/Kotlin. O GitHub executa testes e build antes de
publicar o artefato. Não houve teste visual Android nem ensaio com o módulo.

## Dados específicos a solicitar ao desenvolvedor

1. `FlexProgram.cpp` e `FlexProgram.h` (principal lacuna atual).
2. Confirmação de que a identificação 2.0 usa a RAM/EEPROM da planilha FLEX V10.0.
3. Captura TX/RX do aprendizado completo de Sonda/RPM e MAP no software original.
4. Condições de motor, mensagens e critério exato de término de cada aprendizado.
5. Significado vigente dos bits 02/04/08/10 e unidade do LAMBDA_WAIT.
6. Escala real de DELTA_ALFA e curva de entrada de sonda larga usada no módulo.
7. Regras de escrita EEPROM, limites, persistência, ACK e recuperação de falha.

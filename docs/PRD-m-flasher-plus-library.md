# M-Flasher + Library — PRD

**Status:** Draft
**Versão:** 1.0
**Autor:** Wellington Saam
**Data:** 2026-11-15
**Stakeholders:** Wellington Saam (produto e uso); autores dos firmwares da comunidade (atribuição); The Firmware Hub (catálogo); projeto upstream M-Flasher / m-bootloader (licença MIT)

---

## 1. Resumo Executivo

Estamos construindo o **M-Flasher + Library**, um fork do M-Flasher para **desktop (macOS, Windows, Linux) e Android**. Ele junta, num só app, a biblioteca de firmwares do **The Firmware Hub** guardada localmente e o mecanismo de gravação seguro do M-Flasher, que usa o modo download da ROM do AC791N. O público são donos de M-VAVE FM-1 (e Fame MiniFM) que querem trocar de firmware com frequência sem arriscar a unidade.

O app detecta o chip de flash da unidade, identifica firmware ou bootloader corrompidos, restaura o firmware original da M-VAVE (015/016, a partir dos pacotes oficiais do usuário), instala firmwares da biblioteca compatíveis com o chip e garante que a unidade tenha o **m-bootloader**. Com o m-bootloader, um firmware que falha deixa de ser um brick e passa a ser uma reinstalação pelas teclas. O resultado esperado é **nenhuma unidade irrecuperável** causada por uma instalação feita pelo app.

---

## 2. Declaração do Problema

### Problema
A comunidade do FM-1 tem mais de 200 firmwares alternativos, instalados pelo navegador (FirmwareHub) ou por instaladores próprios. Esses caminhos **não tocam no bootloader**, e a maior parte das unidades continua com o **bootloader original da M-VAVE**, que não tem recuperação pelas teclas. Quando um firmware falha nessa combinação, a unidade só volta com o M-Flasher no computador (se cair sozinha no modo download) ou com um dongle USB_KEY (Raspberry Pi Pico). Além disso:
- não há uma biblioteca **offline** de firmwares com integridade verificada;
- o usuário precisa juntar várias ferramentas: hub no navegador, M-Flasher no computador e pacotes oficiais baixados à parte;
- nada impede instalar um firmware feito só para o chip de flash antigo num FM-1 com o chip novo;
- não existe caminho pelo **celular** para o modo download.

### Evidências
- O catálogo do hub (`catalog.json`, em 2026-11-15) lista **217 firmwares para o FM-1**, dos quais **72 estão marcados `"chip": "old"`**. As regras do hub proíbem instalar esses pacotes num FM-1 com o chip novo.
- O README do M-Flasher diz: "A unit with the stock bootloader and stock firmware cannot enter download mode from its keys".
- `docs/usage.md:86-88`: "If the firmware does not start, the USB_KEY dongle (a Raspberry Pi Pico) still reaches download mode".
- `web/README.md`: a versão web não resgata "a stock unit that won't start". Para isso é preciso o M-Flasher no computador ou o dongle.
- O hub hospeda só o firmware original 015. A versão 016 fica fora dele.

### Quem tem esse problema?
Donos de FM-1/MiniFM que experimentam firmwares da comunidade, sobretudo os que ainda têm o bootloader original.

### Por que resolver agora?
O catálogo do hub cresce todos os dias (verificação noturna das releases). Os FM-1 vendidos a partir do fim de 2026 vêm com outro chip de flash (UCUN `0xB36014`), o que aumenta o risco de instalar um pacote incompatível com o chip.

---

## 3. Usuários-Alvo & Personas

### Persona Primária
- **Nome:** "O experimentador": dono de FM-1 que troca de firmware toda semana.
- **Perfil:** músico ou maker, usuário técnico intermediário, com Mac e Android.
- **Jobs-to-be-done:** testar firmwares novos rapidamente; voltar ao firmware original quando quiser; levar o FM-1 e o celular para fora de casa e conseguir reinstalar sem computador.
- **Dores:** medo de brick, ferramentas espalhadas, dependência de internet e do navegador, dúvida sobre qual chip a unidade tem.
- **Ganhos esperados:** uma biblioteca local verificada, instalação em poucos toques e recuperação garantida.

### Persona Secundária
- **"O socorrista":** quem ajuda amigos ou a comunidade a recuperar unidades com brick, inclusive a partir de backups de outras unidades.

---

## 4. Contexto Estratégico

- **Natureza:** projeto pessoal e open source (fork MIT). Não há OKRs corporativos.
- **Landscape:**
  - **The Firmware Hub:** instala pelo navegador via USB MIDI e tem o melhor catálogo, mas não alcança o modo download nem troca o bootloader.
  - **M-Flasher (desktop):** gravação segura completa, mas sem biblioteca e sem Android.
  - **M-Flasher Web (beta):** só modo MIDI ou "stage"; não resgata unidade com bootloader original.
- **Diferencial:** biblioteca offline + modo download + Android + garantia do m-bootloader.

---

## 5. Visão Geral da Solução

### Descrição
Um app que reaproveita o núcleo do M-Flasher sem reescrever a lógica de segurança e acrescenta:
1. uma **biblioteca local** sincronizada com o catálogo do hub, com os pacotes baixados das releases dos autores e verificados por SHA-256;
2. **pacotes oficiais importados** da pasta do usuário (FM-1 015 e 016);
3. um **assistente** que diagnostica a unidade e conduz a instalação, com a garantia do m-bootloader;
4. um **app Android** com o mesmo núcleo, falando com o modo download por USB OTG.

### Fluxo do Usuário (alto nível)
1. Conecta o FM-1. O app passa a unidade para o modo download (soft key MIDI, ou teclas, se ela já tiver o m-bootloader).
2. O app lê a flash (leitura conferida pelo CRC32 do chip) e mostra o **diagnóstico**: modelo, chip de flash (antigo ou novo), bootloader, firmware e estado do firmware.
3. Se a unidade tiver o bootloader original, o app **recomenda o m-bootloader** (REQ-BOOT-01).
4. O usuário escolhe um firmware da biblioteca (já filtrada pelo chip) ou "Restaurar original M-VAVE".
5. O app mostra o **plano**: setores, avisos e ordem (m-bootloader → firmware). Faz o backup, grava, verifica e reavalia a unidade.

### Features Principais
- **Diagnóstico:** modelo, chip de flash, bootloader e estado do firmware.
- **Biblioteca:** catálogo do hub + download verificado + cache offline + filtro por chip.
- **Original M-VAVE:** importação e validação de 015 e 016 e restauração de firmware e/ou bootloader originais.
- **Garantia do m-bootloader** (REQ-BOOT-01).
- **Backups:** automático antes de toda primeira gravação; restauração com verificação SHA-256.
- **Android:** mesmo núcleo, USB OTG e MIDI nativos.

---

## 6. Métricas de Sucesso

Projeto pessoal, sem baseline de uso. As métricas são de segurança e de eficácia.

### Métrica Principal (North Star)
| Métrica | Atual | Meta | Prazo |
|---|---|---|---|
| Unidades irrecuperáveis causadas por instalações feitas pelo app | n/a | **0** | permanente |

### Métricas Secundárias
| Métrica | Atual | Meta |
|---|---|---|
| Gravações precedidas de backup salvo e sincronizado em disco | n/a | 100% |
| Pacotes instalados com SHA-256 conferido com o catálogo ou com o hash de referência | n/a | 100% |
| Instalações bloqueadas de pacotes `chip: old` em FM-1 com chip novo | n/a | 100% |
| Unidades que terminam o fluxo de instalação com m-bootloader (quando o usuário aceita) | n/a | 100% |
| Tempo de "conectar" até "firmware da biblioteca instalado e verificado" (desktop, pacote em cache) | n/a | < 2 min |
| Mesmo fluxo no Android | n/a | < 4 min |
| Leitura e backup da flash completa no Android (spike) | n/a | funciona em pelo menos 1 aparelho de referência |

---

## 7. User Stories & Requisitos

### Epic
**Como** dono de FM-1, **quero** instalar qualquer firmware da comunidade ou o original da M-VAVE a partir de uma biblioteca local, no computador ou no celular, **para** experimentar sem nunca deixar a unidade sem recuperação.

**US-001: Diagnóstico da unidade**
```
Como dono de FM-1,
Quero ver ao conectar o modelo, o chip de flash, o bootloader e o estado do firmware,
Para saber o que posso instalar e se há algo corrompido.

Critérios de Aceite:
- [ ] Mostra o chip como "antigo (0x856014)", "novo (0xB36014)" ou "desconhecido (0x……)".
- [ ] Mostra o bootloader: original M-VAVE, m-bootloader <versão>, danificado ou ilegível.
- [ ] Mostra o firmware (nome e versão) e o estado: OK / danificado / gravação interrompida / ausente.
- [ ] Se duas leituras da flash divergirem, ou o CRC32 do chip não bater, o app não permite gravar e explica a causa.
- [ ] Se o modelo não for identificado com certeza, o app pede ao usuário que escolha e bloqueia a recomendação do m-bootloader.
```

**US-002: Detecção e correção de firmware corrompido**
```
Como dono de uma unidade que não inicia,
Quero que o app detecte o firmware corrompido e proponha a correção,
Para recuperar a unidade sem diagnóstico manual.

Critérios de Aceite:
- [ ] Firmware "danificado", "gravação interrompida" ou "ausente" gera um alerta com duas ações: "Restaurar original M-VAVE" e "Reinstalar firmware anterior" (se houver backup ou pacote em cache).
- [ ] Bootloader "danificado/ilegível" gera um alerta com as ações "Restaurar bootloader original (de pacote oficial)" e "Instalar m-bootloader".
- [ ] Nenhuma ação é executada sem o plano revisado e confirmado.
```

**US-003: Importar os pacotes originais M-VAVE**
```
Como dono de FM-1,
Quero apontar a pasta onde estão meus pacotes oficiais (015 e 016),
Para restaurar o original mesmo sem internet e mesmo a versão que o hub não hospeda.

Critérios de Aceite:
- [ ] Aceita arquivos .fwsc de uma pasta escolhida pelo usuário (no Android, pelo seletor de arquivos).
- [ ] Reconhece por SHA-256: FM-1_015 = db1642b2…db8a (igual ao "stock" do hub), FM-1_016 = d017fb65…61d5.
- [ ] Pacote com hash desconhecido é aceito apenas como "não verificado", com aviso, e só depois de passar em Package::parse e check_boot_area.
- [ ] Pacotes oficiais não são embutidos no app distribuído; ficam só na cópia local do usuário.
```

**US-004: Restaurar o original M-VAVE**
```
Como dono de FM-1,
Quero voltar ao firmware original, com ou sem o bootloader original,
Para devolver a unidade ao estado de fábrica ou partir de uma base conhecida.

Critérios de Aceite:
- [ ] Opção "só firmware": usa plan_firmware e mantém o bootloader atual.
- [ ] Opção "firmware + bootloader original": usa plan_bootloader com o pacote oficial e grava a boot area inteira do pacote.
- [ ] Ao ir de 016 para 015, avisa que a área USR (sons) só volta com um backup anterior à 016 e oferece restaurar esse backup, se existir.
- [ ] Com "bootloader original" escolhido, o app não recomenda o m-bootloader nessa sessão (REQ-BOOT-01, item 5).
```

**US-005: Sincronizar a biblioteca do FirmwareHub**
```
Como experimentador,
Quero uma biblioteca local com os firmwares do hub,
Para escolher e instalar mesmo offline.

Critérios de Aceite:
- [ ] Lê https://thefirmwarehub.com/catalog.json no máximo 1 vez por dia, com User-Agent próprio (ex.: "m-flasher-plus/<versão> (+<url do repo>)").
- [ ] Baixa cada .fwsc da release do autor no GitHub (campos repo, tag, release e o padrão assetPkg), nunca de thefirmwarehub.com/fw/.
- [ ] Só guarda o pacote se o SHA-256 bater com o do catálogo; caso contrário, descarta e registra o erro.
- [ ] Downloads sob demanda (ao escolher o firmware) ou em lote, se o usuário pedir "baixar tudo"; o lote respeita o limite da API do GitHub.
- [ ] Firmwares que saírem do catálogo são marcados como removidos e escondidos (o arquivo local fica até o usuário apagar).
- [ ] Itens linkOnly, externalInstall ou sem pacote são exibidos como "instalar no site do autor", sem instalação pelo app.
- [ ] Mostra a atribuição "Firmware list: The Firmware Hub – https://thefirmwarehub.com" (CC BY 4.0) e, em cada firmware, o autor, a licença e o link do repositório.
```

**US-006: Filtro por chip de flash**
```
Como dono de um FM-1 com o chip novo,
Quero que o app só me deixe instalar firmwares compatíveis,
Para não quebrar a unidade com um pacote feito para o chip antigo.

Critérios de Aceite:
- [ ] Unidade com chip novo (0xB36014): pacotes "chip": "old" aparecem desabilitados, com o motivo, e não podem ser instalados nem pela linha de comando.
- [ ] Unidade com chip antigo: todos os pacotes FM-1 ficam disponíveis (sujeito à pergunta em aberto Q2).
- [ ] Chip desconhecido: só pacotes "any" e o original M-VAVE, com aviso.
```

**US-007: Instalar um firmware da biblioteca (com REQ-BOOT-01)**
```
Como experimentador,
Quero escolher um firmware e instalá-lo com um plano claro,
Para trocar de firmware com segurança.

Critérios de Aceite:
- [ ] O plano lista em ordem: (1) m-bootloader, se a unidade tiver o original e o usuário aceitar; (2) o firmware.
- [ ] Mostra setores, faixas de endereços e avisos (layout de dados diferente, modelo diferente, chip não testado).
- [ ] Backup completo salvo e sincronizado em disco antes da primeira gravação da sessão.
- [ ] Cada trecho gravado é verificado (CRC32 do chip); em caso de diferença, regrava uma vez e depois para com mensagem e caminho do backup.
- [ ] Ao final, relê a boot area e o estado do firmware e mostra o resultado verificado.
```

**US-008: Backups e restauração**
```
Como usuário,
Quero que todo backup seja verificável e restaurável,
Para desfazer qualquer instalação.

Critérios de Aceite:
- [ ] Mantém o formato .bin + .json do M-Flasher (sha256, unit id, chip key, modelo, ferramenta).
- [ ] Na restauração, recusa um backup cujo SHA-256 não bata com o .json.
- [ ] Backup de outra unidade nunca sobrescreve os dados de fábrica (key_mac em 0xFF000; no SMK-37, também 0xC1000).
- [ ] No Android, os backups ficam no armazenamento do app e podem ser exportados pelo seletor de arquivos.
```

**US-009: Android, conexão e modo download**
```
Como usuário de Android,
Quero conectar o FM-1 por USB OTG e fazer tudo o que o desktop faz,
Para recuperar ou trocar firmware sem computador.

Critérios de Aceite:
- [ ] Pede a permissão USB e abre o dispositivo "WL82 UBOOT1.00" pelo descritor de arquivo (sem root).
- [ ] Envia o soft key MIDI (F0 22 24 35 7D F7) a uma unidade em funcionamento pela API android.media.midi.
- [ ] Fluxos US-001 a US-008 funcionam com o mesmo núcleo Rust.
- [ ] Explica claramente quando a porta USB do aparelho não fornece energia suficiente ou não suporta OTG.
```

### REQ-BOOT-01: Garantir o m-bootloader na unidade

1. **Ao conectar:** se a unidade tiver o bootloader original, modelo identificado com certeza, chip de flash testado e chip key íntegra, recomendar a instalação do m-bootloader (com a opção de recusar).
2. **No fluxo "instalar firmware da biblioteca":** se a unidade tiver o bootloader original, oferecer o m-bootloader **antes** do firmware, como opção padrão, para que a recuperação pelas teclas já esteja disponível caso o firmware novo não inicie.
3. **Depois de qualquer instalação de firmware**, inclusive uma feita fora do app (hub ou instaladores próprios): reler a boot area e, se o bootloader ainda for o original, repetir a recomendação.
4. **Nunca instalar automaticamente.** Mostrar o plano de setores, salvar o backup antes e verificar depois, como em toda gravação do M-Flasher.
5. **Exceção:** quando o usuário escolher "restaurar o firmware original da M-VAVE com o bootloader original", não recomendar o m-bootloader nessa sessão.

> Nota: o M-Flasher upstream recomenda o m-bootloader ao conectar a qualquer unidade com bootloader original (`bootloader_suggestion`, `src/ops.rs:213`), independentemente do firmware instalado. Os itens 2 e 3 são extensões deste projeto.

### Requisitos Não-Funcionais
- **Segurança da gravação:** manter, sem enfraquecer, todas as regras de `docs/safety.md` (leitura dupla com CRC32, cerca de setores em `Plan::new`, backup sincronizado em disco, verificação depois de gravar, recusa de boot areas que não dão boot e de chip key incompatível).
- **Integridade:** SHA-256 obrigatório para todo pacote da biblioteca; os hashes de referência dos originais ficam no código.
- **Offline:** diagnóstico, restauração do original e instalação a partir do cache funcionam sem internet.
- **Rede:** catálogo no máximo 1×/dia; downloads só do GitHub; User-Agent identificável.
- **Compatibilidade:** macOS 12+, Windows 10+, Linux (x86_64), Android 9+ (API 28) com USB host.
- **Privacidade:** nenhuma telemetria. O unit id e os backups ficam apenas no aparelho.
- **Licenças:** fork sob MIT com o copyright original; atribuição CC BY 4.0 ao hub; autor, licença e repositório de cada firmware exibidos; pacotes GPL não embutidos no app.

---

## 8. Fora do Escopo

**NÃO está incluído nesta versão:**
- SMK-37 Pro/Elite na biblioteca. Motivo: foco no FM-1. O núcleo já suporta o SMK-37 e o catálogo tem a seção `smk37`; fica para a v2.
- Instalação pelo modo MIDI ou "stage" (estilo M-Flasher Web). Motivo: a v1 usa o modo download, que cobre todos os casos.
- Conversão de pacotes entre chips (o hub tem `/api/convert`). Motivo: complexidade e risco.
- iOS. Motivo: não há acesso USB host equivalente para mass storage.
- Contas, favoritos e avaliações do hub.
- Assinatura de código dos binários.

**Considerações Futuras (v2+):**
- Suporte ao SMK-37 na biblioteca.
- Modo MIDI do m-bootloader como transporte alternativo (útil quando o OTG falhar).
- Notificação de novas versões dos firmwares instalados.

---

## 9. Considerações Técnicas

- **Arquitetura:**
  - Separar do M-Flasher um crate `mflasher-plus-core` (`formats`, `boards`, `ops`, `jl`, `sectormap`). Ele já é independente de transporte pelos traits `Scsi` e `Flash`.
  - Novo módulo `library`: catálogo (cache + ETag), resolução do asset de release no GitHub (`repo` + `tag` + regex `assetPkg`), download, SHA-256 e índice local.
  - Novo módulo `diagnose`: encapsula `Analysis`, `flash_text`, `app_files_status` e `BootKind` e produz as ações sugeridas.
  - Novo módulo `wizard`: monta a sequência de planos (bootloader → firmware) e aplica o REQ-BOOT-01.
- **Desktop:** o binário egui atual com uma aba "Biblioteca" e o assistente. Os transportes existentes continuam (SG_IO, SCSI_PASS_THROUGH, libusb + helper root no macOS, helper UAC no Windows).
- **Android:** app Kotlin/Jetpack Compose. Núcleo Rust via `cargo-ndk` + `uniffi`. Transporte: `UsbManager` → `UsbDeviceConnection.getFileDescriptor()` → `rusb` `open_device_with_fd` → o Bulk-Only Transport já existente em `src/usb.rs`. MIDI pela API `android.media.midi`.
- **Dependências:** catálogo do The Firmware Hub; releases do GitHub dos autores; binários do m-bootloader (`data/`, MIT); rusb/libusb.
- **Armazenamento local:** `<dados do app>/library/{catalog.json, packages/<id>-<versão>.fwsc, index.json}`, `<dados do app>/stock/` (pacotes importados) e `<dados do app>/backups/`.
- **Testes:** manter `cargo test`; rodar com `FLASHER_TEST_DATA` incluindo `fm1-016.fwsc` (o pacote do usuário confere com o hash de referência); novos testes para o filtro por chip, o REQ-BOOT-01 e a verificação de SHA-256 da biblioteca, sobre a flash simulada já existente em `src/tests.rs`.
- **Upstream:** manter o fork rebaseável. Mudanças no núcleo, só aditivas; propor upstream o que for genérico (por exemplo, a extração do crate).

---

## 10. Design & UX

- **Telas:** Diagnóstico (cartão da unidade + mapa da flash) → Biblioteca (lista filtrável por tipo, chip e beta) → Detalhe do firmware (autor, licença, versão, link) → Plano (setores, avisos, ordem) → Progresso → Resultado verificado.
- **Estados de erro:** leitura instável (trocar cabo ou porta), chip desconhecido, pacote com hash divergente, sem internet (usar cache), OTG sem energia, unidade saiu do modo download (cerca de 30 s sem atividade).
- **Linguagem:** português e inglês. Textos de risco sempre explícitos ("Não desligue a unidade").
- **Android:** layout para celular com uma ação principal por tela; manter a tela ligada durante a gravação.

---

## 11. Riscos & Mitigações

| Risco | Probabilidade | Impacto | Mitigação |
|---|---|---|---|
| USB OTG não funciona ou não alimenta o FM-1 em alguns Androids | Média | Alto | Spike na Fase 0; lista de aparelhos testados; mensagem clara; usar hub OTG com fonte |
| Android monta o "WL82 UBOOT1.00" como disco e bloqueia o acesso | Média | Alto | `claimInterface(force=true)`; validar no spike |
| Significado de `chip` no catálogo diferente do suposto (old/new ↔ JEDEC) | Média | Alto | Pergunta Q1/Q2 ao hub antes de liberar o filtro; na dúvida, bloquear |
| Limite da API do GitHub (60 req/h sem autenticação) | Média | Baixo | Download sob demanda; cache; token opcional do usuário |
| Asset de release renomeado ou sem `tag` | Baixa | Médio | Usar `assetPkg` + `release`; conferir sempre o SHA-256; se não encontrar, "instalar no site do autor" |
| m-bootloader em chip de flash novo (issue #13 upstream) | Baixa | Alto | Aviso já existente; backup obrigatório; documentar |
| Divergência do upstream complica atualizações | Média | Médio | Núcleo aditivo; rebase periódico |
| Usuário vai de 016 para 015 e perde os sons (USR) | Média | Médio | Aviso + oferta de restaurar o backup anterior à 016 (US-004) |

---

## 12. Dependências & Premissas

### Dependências
- A extração do `mflasher-plus-core` (Fase 1) vem antes do Android (Fase 3).
- O spike Android (Fase 0) decide se a Fase 3 continua como planejada.
- O filtro por chip depende de confirmar a semântica do campo `chip` (Q1, Q2).

### Premissas
- Os pacotes oficiais do usuário em `.../3. Recursos/M-Vave FM1 Firwmare/` são autênticos (verificado em 2026-11-15 por SHA-256: 015 = `db1642b2…db8a`, 016 = `d017fb65…61d5`).
- O catálogo continua público, em CC BY 4.0 e com os campos atuais (`pkg`, `sha256`, `chip`, `repo`, `tag`, `release`, `assetPkg`, `lic`).
- O usuário de referência tem um FM-1 e um Android com USB host.

---

## 13. Timeline & Milestones

| Fase | Entregável | Estimativa |
|---|---|---|
| 0. Spike Android | App mínimo lê a flash e salva o backup via OTG (núcleo Rust + fd) | 1–2 dias |
| 1. Núcleo | `mflasher-plus-core` extraído; testes upstream passando com `fm1-016.fwsc` | 2–3 dias |
| 2. Desktop | Diagnóstico, importação dos originais, biblioteca, filtro por chip, assistente com REQ-BOOT-01 | 1–2 semanas |
| 3. Android | Mesmos fluxos no app Kotlin | 2–3 semanas |
| 4. QA em unidade real | Matriz: chip antigo/novo × bootloader original/m-bootloader × 015/016/custom | 1 semana |
| 5. Release | Builds desktop + APK; documentação de segurança e atribuições | 2–3 dias |

---

## 14. Perguntas em Aberto

- [x] **Q1.** Respondida em 2026-11-16 (thefirmwarehub.com/kb): `old` = o firmware só conhece o chip antigo `0x856014`; num FM-1 com `0xB36014` ele não grava a flash (configurações somem, atualizações falham). Regra do hub: nunca instalar `"chip": "old"` num FM-1 com o chip novo. O app já bloqueia exatamente isso.
- [x] **Q2.** Respondida em 2026-11-16: `new` = conhece o chip novo e serve nos dois (o hub fala em firmwares "que suportam os dois chips"). Confirmado na prática: Felucca 1.8.2 (`new`) instalado pela biblioteca no FM-1 preto (`0x856014`) e funcionando. `any` (5 firmwares) não depende do chip. Catálogo de 2026-11-16: 141 `new`, 71 `old`, 5 `any`.
- [ ] **Q3.** O soft key MIDI funciona no modo MIDI do m-bootloader, ou só no firmware? Isso afeta o fluxo de entrada no modo download.
- [ ] **Q4.** Os itens com `loader` preenchido no catálogo exigem um bootloader específico? Interação com o REQ-BOOT-01.
- [ ] **Q5.** O app deve permitir importar firmwares fora do catálogo (arquivo `.fwsc` avulso)? Se sim, com quais checagens além de `Package::parse` e `check_boot_area`?
- [ ] **Q6.** Distribuição: só uso pessoal, ou publicar o fork (e então definir o nome, já que "M-Flasher" é do autor upstream)?
- [ ] **Q7.** Portas MIDI com nome próprio: firmwares derivados (ex.: fm1-dock, porta "Caravel", produto USB "Felucca") não batem com a lista de nomes do M-Flasher (`src/midi.rs`, `NAMES`), e o soft key não é enviado. Reconhecer pelo VID/PID USB (ex.: `1209:0001`), por uma lista vinda do catálogo, ou permitir escolher a porta manualmente?
- [ ] **Q8.** Unit ID não é único: dois FM-1 diferentes (preto e laranja, chip `0x856014`, 2026-11-15) informaram o mesmo `unit id` `4150354433333610`, embora 230 de 256 setores sejam diferentes. `plan_restore` confia nesse ID para decidir se o backup é "da mesma unidade" e, nesse caso, restaura também os dados de fábrica. Nos dois aparelhos o `key_mac` está vazio (`0xFF`), então hoje não houve dano, mas o app não pode confiar só no unit id. Confirmado ao vivo nos dois aparelhos e reportado upstream: https://github.com/jvitkauskas/m-flasher/issues/61

## Achados em aparelho (2026-11-15)

| Unidade | Chip | Bootloader | Firmware | Unit ID | Backup |
|---|---|---|---|---|---|
| FM-1 preto | `0x856014` (antigo) | original → **m-bootloader 2.4** | FM-1 921 (fm1-dock) → **FM-1 016** | `4150354433333610` | `~/Documents/FM1-backups/preto/` |
| FM-1 laranja | `0x856014` (antigo) | original → **m-bootloader 2.4** | Felucca (porta MIDI "Caravel") → **FM-1 016** | `4150354433333610` (igual ao preto) | `~/Documents/FM1-backups/laranja/` (`…213157` original, `…213310` antes do m-bootloader, `…213450` Felucca + m-bootloader) |

- Entrar no modo download com fm1-dock/Felucca rodando: OCT−+OCT+ com o aparelho ligado não funciona no fm1-dock (o resgate dele é OCT− ao ligar, que abre o atualizador do firmware e não a ROM); o SysEx `F0 22 24 35 7D F7` enviado para a porta "Caravel" funcionou nas duas unidades.
- No macOS, o pedido de senha do M-Flasher não aparece quando ele é executado pelo Claude Code: as gravações precisam rodar com `sudo` no Terminal do usuário.

---

## Checklist de Aprovação

- [ ] Viabilidade técnica revisada (resultado do spike Android)
- [ ] Requisitos de UX revisados
- [ ] Escopo aprovado
- [ ] Timeline validada
- [ ] Métricas de sucesso acordadas
- [ ] Aprovação registrada

## Spike Android: resultado (2026-11-15)

**Passou.** Redmi 10 (Android 13, MIUI 14, arm64), FM-1 preto em modo download, cabo USB-C ↔ USB-C:

- O app (`android/`, sem Gradle: `android/build.sh`) encontrou o FM-1 em modo download (`4C4A:8057`), recebeu a permissão USB, assumiu a interface de mass storage e passou o file descriptor ao núcleo Rust.
- O núcleo é o código do M-Flasher **sem alterações** (`src/formats.rs`, `boards.rs`, `scsi.rs`, `jl.rs`, `ops.rs`, incluídos por caminho). Só o transporte é novo: `android/rust/src/usbfs.rs` (Bulk-Only Transport por ioctls usbfs, no lugar da libusb).
- Leitura da flash inteira em 1,2 s (823 KB/s, a mesma velocidade do Mac), conferida pelo CRC32 do chip. Backup `.bin` + `.json` salvo no celular, com SHA-256 conferido no Mac.
- O diagnóstico é idêntico ao do desktop (modelo, m-bootloader 2.4, FM-1 016, chip `0x856014`).
- A área de boot do backup é idêntica à do backup do FM-1 preto feito no Mac, e diferente da do laranja: dá para distinguir as unidades pelo conteúdo, apesar do unit id repetido (Q8).

Aprendizados:
- O firmware 016 rodando aparece no USB como `4C4A:C755` "FM-1", com o mesmo fabricante do modo download: o app pode reconhecer o FM-1 rodando pelo VID, sem depender do nome da porta MIDI (Q7).
- A MIUI recusa instalação por `adb install` (`INSTALL_FAILED_USER_RESTRICTED`) mesmo com "Instalar via USB" ativo; o caminho que funcionou foi copiar o APK para Downloads e instalar pelo gerenciador de arquivos.
- `adb` por Wi-Fi permite ler os logs com a porta USB do celular ocupada pelo FM-1.
- O `.json` grava `"tool": "m-flasher 0.1.0"` (a versão do crate Android): ajustar para o nome e a versão do app.

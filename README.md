# Coletor Evelog

Aplicativo Android nativo em Kotlin para o Zebra TC52. O padrão aceito é exatamente **14 algarismos + `$` + 12 algarismos**, por exemplo `10978702520938$001568515000`. Espaços ou qualquer caractere extra tornam a leitura inválida.

## Recursos

- Recebe leituras pelo Zebra DataWedge ou permite digitação manual.
- Na leitura pelo scanner ou digitação manual, insere o separador `$` após os 14 primeiros dígitos quando a entrada vier com 26 dígitos sem o símbolo. O campo manual limita a entrada a 27 caracteres.
- Mostra retorno visual e sonoro para etiqueta aceita, formato inválido e duplicata. Leituras inválidas/duplicadas também vibram.
- Organiza a operação em lotes identificados como **Lote Rua [número]**, com número da rua, operador e tipo (Transferência ou Entrada) obrigatórios. Lotes sem etiquetas são descartados e nunca gravados.
- Registra e exibe as etiquetas em sequência de leitura, sem agrupá-las por Shipment ID. O TXT mantém no cabeçalho os totais de Shipment IDs e etiquetas para análise em outro sistema.
- A senha `admin` é exigida para fechar lotes com etiquetas e para apagar lotes selecionados.
- Retoma automaticamente um lote que ficou aberto se o app for fechado ou o coletor reiniciar.
- Aceita apenas um lote aberto por vez e bloqueia códigos duplicados também entre lotes.
- Em **⋮ > Configurações**, selecione uma pasta de destino, inclusive no Google Drive. Ao fechar o lote, exporta automaticamente um TXT com tipo, identificação Lote Rua, operador, data/hora, totais de Shipment IDs/etiquetas e os códigos em sequência. O histórico mostra o estado da exportação e permite repetir exportações pendentes.
- O nome do APK gerado inclui o nome do app e a versão.
- Mantém o histórico de lotes no aparelho; cada item pode ser expandido para mostrar os códigos em sequência. É possível selecionar lotes para apagar ou selecionar todos, sempre com confirmação.
- O menu de três pontos mostra a tela **Sobre** com a versão instalada.
- Migra automaticamente os registros da versão anterior para um lote fechado chamado **Leituras anteriores**.

## Configurar o DataWedge no TC52

Na primeira abertura, o app envia ao DataWedge a solicitação oficial `SET_CONFIG` para criar/atualizar o perfil `ColetorEvelog`, associá-lo a todas as atividades do app e ativar o scanner interno com **Intent output**. O app recebe a leitura pelo extra padrão `com.symbol.datawedge.data_string`. Feche e abra o app uma vez após instalar/atualizar para aplicar o perfil.

Se a política corporativa do coletor restringir APIs do DataWedge, o administrador do dispositivo precisa permitir que o app use essas APIs. A versão do DataWedge pode variar conforme o Android/BSP instalado no TC52.

## Gerar o APK

Abra a pasta no Android Studio e aguarde a sincronização do Gradle. O módulo `app` define `minSdk 23`, `targetSdk 35` e `compileSdk 35`. Gere o APK por **Build > Build Bundle(s) / APK(s) > Build APK(s)**. A versão atual é 1.24 (versionCode 25); o APK gerado é nomeado `Coletor-Evelog-v1.24.apk`.

# Exemplo de repositório

`ruivo-repository.example.json` mostra o formato aceito pelo RuivoTV. Antes de adicioná-lo no app:

1. Hospede o `demo-addon.zip` e este índice em URLs HTTPS públicas, sem redirecionamento.
2. Substitua `https://SEU-DOMINIO.example/demo-addon.zip` pelo URL real do ZIP.
3. Recalcule o SHA-256 depois de qualquer alteração no ZIP (`sha256sum demo-addon.zip`) e atualize o campo `sha256`.
4. Publique o índice JSON em HTTPS e adicione seu URL na aba **Repositórios**.

O SHA-256 detecta corrupção/alteração em relação ao hash publicado, mas não autentica quem publicou o índice ou o pacote. Use apenas repositórios e conteúdo em que você confia. O pacote de demonstração contém somente JSON e README, e usa streams fictícios não funcionais.

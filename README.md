# PNC Groovy Client

A minimal [Newcastle](https://github.com/project-ncl/pnc) REST API Client written in Groovy.

## Usage

### CLI

```bash
alias pgc='java -jar target/pnc-groovy-client-1.0.0-SNAPSHOT-executable.jar'
echo 'pnc.url=http://orch.example.com/pnc-rest/rest/swagger.json' > $HOME/.config/pgc.properties
pgc -h
pgc list -h
pgc call -h
pnc_version="$(pgc call build-records get-specific -a id=7113 | jq -r '.executionRootVersion')"
```

### API

```groovy
def pnc = new PncClient('http://orch.example.com/pnc-rest/rest/swagger.json')
Map buildInfo = pnc.exec(
    'buildRecords', 'getSpecific',
    id: 7113,
)
String pncVersion = buildInfo['executionRootVersion']
```

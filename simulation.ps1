$jsonJar = Get-ChildItem "$env:USERPROFILE\.m2\repository\org\json\json" `
    -Recurse -Filter "*.jar" |
    Sort-Object FullName |
    Select-Object -Last 1 -ExpandProperty FullName

javac -cp $jsonJar Simulation.java CustomerSimulation.java SimulationLogger.java

if ($LASTEXITCODE -eq 0) {
    java `
        "-Djavax.net.ssl.trustStore=$PWD\simulator-truststore.p12" `
        "-Djavax.net.ssl.trustStoreType=PKCS12" `
        "-Djavax.net.ssl.trustStorePassword=password" `
        -cp ".;$jsonJar" `
        Simulation
}
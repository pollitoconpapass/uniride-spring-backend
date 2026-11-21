param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$Test = ''
)

# Creates a fresh loopback-only PostgreSQL cluster. It never accepts a database URL.
# Retains the stopped temporary cluster and log for inspection; no destructive cleanup.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$clusterRoot = Join-Path ([IO.Path]::GetTempPath()) ('uniride-agent-pg-' + [Guid]::NewGuid().ToString('N'))
$clusterData = Join-Path $clusterRoot 'data'
$clusterLog = Join-Path $clusterRoot 'postgres.log'
$postgresStarted = $false
$originalTestUrl = $env:UNIRIDE_TEST_PG_URL
$originalTestUser = $env:UNIRIDE_TEST_PG_USER
$originalTestPassword = $env:UNIRIDE_TEST_PG_PASSWORD
$originalLocation = Get-Location

foreach ($binary in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin $binary) -PathType Leaf)) {
        throw "PostgreSQL binary not found: $binary in $PostgresBin"
    }
}

# Reserve an ephemeral loopback port, release it immediately before PostgreSQL starts.
$portReservation = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portReservation.Start()
$postgresPort = $portReservation.LocalEndpoint.Port
$portReservation.Stop()
New-Item -ItemType Directory -Path $clusterRoot | Out-Null
Write-Host "Isolated PostgreSQL directory: $clusterRoot"

try {
    & (Join-Path $PostgresBin 'initdb.exe') -D $clusterData -U uniride_test --auth=trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed; no tests executed.' }

    # Windows children can retain PowerShell's native pipeline handles after pg_ctl exits.
    # Separate redirected files let us wait for pg_ctl itself without waiting for postgres.
    $pgStart = Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru `
        -ArgumentList "-D `"$clusterData`" -l `"$clusterLog`" -o `"-h 127.0.0.1 -p $postgresPort`" -w start" `
        -RedirectStandardOutput (Join-Path $clusterRoot 'start.stdout.log') `
        -RedirectStandardError (Join-Path $clusterRoot 'start.stderr.log')
    # Retain the process handle: Windows PowerShell can otherwise expose a null ExitCode.
    $pgStartHandle = $pgStart.Handle
    if (-not $pgStart.WaitForExit(30000)) {
        throw 'Temporary PostgreSQL did not start; no tests executed.'
    }
    $pgStart.Refresh()
    if ($pgStart.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $clusterData 'postmaster.pid'))) {
        throw 'Temporary PostgreSQL did not start; no tests executed.'
    }
    $postgresStarted = $true

    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $postgresPort -U uniride_test uniride_agent_test
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated test database.' }

    $env:UNIRIDE_TEST_PG_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/uniride_agent_test"
    $env:UNIRIDE_TEST_PG_USER = 'uniride_test'
    $env:UNIRIDE_TEST_PG_PASSWORD = 'uniride_ephemeral_test_only'
    Set-Location -LiteralPath $projectRoot
    # JVM properties take precedence over inherited SPRING_DATASOURCE_*/SPRING_FLYWAY_* variables.
    # Pin both connections explicitly: Flyway can otherwise have its own independent URL.
    $mavenArguments = @('-Ppostgres-it', 'test',
        "-Dspring.datasource.url=$env:UNIRIDE_TEST_PG_URL",
        '-Dspring.datasource.username=uniride_test',
        '-Dspring.datasource.password=uniride_ephemeral_test_only',
        '-Dspring.datasource.driver-class-name=org.postgresql.Driver',
        "-Dspring.flyway.url=$env:UNIRIDE_TEST_PG_URL",
        '-Dspring.flyway.user=uniride_test',
        '-Dspring.flyway.password=uniride_ephemeral_test_only',
        '-Dspring.flyway.enabled=true',
        '-Dspring.flyway.clean-disabled=true',
        '-Dspring.flyway.baseline-on-migrate=false',
        '-Dspring.jpa.hibernate.ddl-auto=validate',
        '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect')
    if ($Test) { $mavenArguments += "-Dtest=$Test" }
    # Native stderr includes JVM/Mockito warnings. Judge Maven by its exit code,
    # not PowerShell's NativeCommandError wrapper for a harmless stderr line.
    $nativeErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & (Join-Path $projectRoot 'mvnw.cmd') @mavenArguments
        $mavenExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $nativeErrorPreference }
    if ($mavenExitCode -ne 0) { throw 'The isolated PostgreSQL test suite failed. Inspect target/surefire-reports.' }
}
finally {
    if ($postgresStarted -or (Test-Path -LiteralPath (Join-Path $clusterData 'postmaster.pid'))) {
        & (Join-Path $PostgresBin 'pg_ctl.exe') -D $clusterData -m fast -w stop
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not stop isolated PostgreSQL. Inspect $clusterRoot" }
    }
    $env:UNIRIDE_TEST_PG_URL = $originalTestUrl
    $env:UNIRIDE_TEST_PG_USER = $originalTestUser
    $env:UNIRIDE_TEST_PG_PASSWORD = $originalTestPassword
    Set-Location -LiteralPath $originalLocation.Path
    Write-Host "Cluster directory retained for inspection: $clusterRoot"
}

web: java $JAVA_OPTS -cp rakam/target/rakam-0.2-SNAPSHOT-bundle/rakam-0.2-SNAPSHOT/lib/*: -Dstore.adapter=postgresql -Dstore.adapter.postgresql.url=${JDBC_DATABASE_URL}&ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory -Dstore.adapter.postgresql.username=${JDBC_DATABASE_USERNAME} -Dstore.adapter.postgresql.password=${JDBC_DATABASE_PASSWORD} -Dplugin.user.enabled=${ENABLE_USER_PLUGIN} -Dplugin.user.mailbox.enable=${ENABLE_USER_MAILBOX_PLUGIN} -Dreal-time.enabled=${ENABLE_REALTIME_PLUGIN} -Devent.stream.enabled=${ENABLE_EVENT_STREAM_PLUGIN} -Devent-explorer.enabled=${ENABLE_EVENT_EXPLORER_PLUGIN} -Duser.funnel-analysis.enabled=${ENABLE_FUNNEL_PLUGIN} -Duser.retention-analysis.enabled=${ENABLE_RETENTION_ANALYSIS_PLUGIN} -Dhttp.server.address=0.0.0.0:${PORT} -Dplugin.geoip.enabled=${ENABLE_GEOIP_PLUGIN} -Dstore.adapter=postgresql -Dplugin.user.storage=postgresql -Dplugin.user.storage.identifier_column=id -Dplugin.user.mailbox.adapter=postgresql -Dstore.adapter.postgresql.max_connection=18 -Dplugin.geoip.database.url=${GEOIP_DATABASE_URL} -Dui.enable=true -Dui.custom-page.backend=jdbc -Dui.directory=rakam-ui/src/main/resources/rakam-ui-master/app org.rakam.ServiceStarter
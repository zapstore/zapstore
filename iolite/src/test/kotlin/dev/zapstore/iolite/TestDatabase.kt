package dev.zapstore.iolite

/** Unit tests open SQLite through JDBC. Production uses [openBundled]. */
internal fun jdbcStore(path: String): IoliteStore =
    IoliteStore(path, openConnection = ::JdbcSqliteConnection)

package com.rshop.scraper.remote

import com.rshop.scraper.config.FtpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens connections to the server of an [FtpConfig]; the password comes from [password] when a connection is made. */
open class RemoteConnector(
    private val config: FtpConfig,
    private val password: suspend () -> String?,
) {
    /** A new connection, logged in. The caller closes it. */
    open suspend fun connect(): RemoteSession {
        val secret = password()
        return withContext(Dispatchers.IO) {
            when (config.protocol) {
                RemoteProtocol.Sftp -> SftpSession.connect(config, secret)
                RemoteProtocol.Ftp, RemoteProtocol.Ftps -> FtpSession.connect(config, secret)
            }
        }
    }
}

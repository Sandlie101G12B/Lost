package code.name.monkey.lost.cast

import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

interface LostSessionManagerListener : SessionManagerListener<CastSession> {
    override fun onSessionResuming(p0: CastSession, p1: String) {}

    override fun onSessionStartFailed(p0: CastSession, p1: Int) {}

    override fun onSessionResumeFailed(p0: CastSession, p1: Int) {}

    override fun onSessionEnding(castSession: CastSession) {}
}
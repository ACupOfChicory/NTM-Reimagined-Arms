package acup.gunfeel;

/** The server's settings come or go (ServerSettings, core): the bullet shapes go by the server's too. */
public final class RemoteShapes implements ServerSettings.Follow {
    @Override
    public void follow(ServerSettings.Snapshot s) {
        PassableClip.useRemoteShapes(s == null ? null : s.shapes());
    }
}

package zm.co.codelabs.adm.storage;

import java.io.IOException;

public final class DiskFullException extends IOException { public DiskFullException() { super("Not enough storage space for this download"); } }

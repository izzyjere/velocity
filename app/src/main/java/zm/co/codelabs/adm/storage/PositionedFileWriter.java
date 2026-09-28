package zm.co.codelabs.adm.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public final class PositionedFileWriter implements Closeable {
    private final RandomAccessFile file;
    private final FileChannel channel;
    public PositionedFileWriter(File target, long preallocateBytes) throws IOException {
        File parent = target.getCanonicalFile().getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IOException("Cannot create destination directory");
        file = new RandomAccessFile(target, "rw");
        if (preallocateBytes > 0 && file.length() != preallocateBytes) file.setLength(preallocateBytes);
        channel = file.getChannel();
    }
    public void write(long absoluteOffset, byte[] data, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        long position = absoluteOffset;
        while (buffer.hasRemaining()) position += channel.write(buffer, position);
    }
    public void force(boolean metadata) throws IOException { channel.force(metadata); }
    public void truncate(long size) throws IOException { channel.truncate(size); }
    public long size() throws IOException { return channel.size(); }
    @Override public void close() throws IOException { channel.close(); file.close(); }
}

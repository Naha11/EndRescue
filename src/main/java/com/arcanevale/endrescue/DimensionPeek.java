package com.arcanevale.endrescue;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Читает из playerdata только одно поле - Dimension.
 *
 * Нужно потому, что когда энд отключён, сервер при входе игрока сам подменяет
 * измерение на обычный мир, но координаты оставляет прежние. К моменту
 * PlayerJoinEvent узнать, что человек сохранился в энде, уже невозможно -
 * эта информация есть только в файле на диске, до его загрузки.
 */
final class DimensionPeek {

    private DimensionPeek() {
    }

    /** @return строка вида "minecraft:the_end" или null, если файла нет либо тега нет */
    static String read(File playerDataFolder, String uuid) {
        File f = new File(playerDataFolder, uuid + ".dat");
        if (!f.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            if (in.readByte() != 10) {
                return null;          // корень обязан быть compound
            }
            in.skipBytes(in.readUnsignedShort());
            return findInCompound(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String findInCompound(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return null;
            }
            int nameLen = in.readUnsignedShort();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            if (type == 8 && name.equals("Dimension")) {
                int len = in.readUnsignedShort();
                byte[] v = new byte[len];
                in.readFully(v);
                return new String(v, java.nio.charset.StandardCharsets.UTF_8);
            }
            skip(in, type);
        }
    }

    private static void skip(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1 -> in.skipBytes(1);
            case 2 -> in.skipBytes(2);
            case 3, 5 -> in.skipBytes(4);
            case 4, 6 -> in.skipBytes(8);
            case 7 -> skipFully(in, (long) in.readInt());
            case 8 -> skipFully(in, in.readUnsignedShort());
            case 9 -> {
                int itemType = in.readUnsignedByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    skip(in, itemType);
                }
            }
            case 10 -> skipCompound(in);
            case 11 -> skipFully(in, 4L * in.readInt());
            case 12 -> skipFully(in, 8L * in.readInt());
            default -> throw new IOException("неизвестный тег NBT: " + type);
        }
    }

    private static void skipCompound(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return;
            }
            in.skipBytes(in.readUnsignedShort());
            skip(in, type);
        }
    }

    /** skipBytes на больших значениях может пропустить меньше, чем просили. */
    private static void skipFully(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long done = in.skip(n);
            if (done <= 0) {
                if (in.read() < 0) {
                    throw new IOException("файл оборвался");
                }
                done = 1;
            }
            n -= done;
        }
    }
}

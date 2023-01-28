import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class Viewer {

    private static final int ARROW_UP = 1000, ARROW_DOWN = 1001, ARROW_RIGHT = 1002, ARROW_LEFT = 1003,
            PAGE_UP = 1004, PAGE_DOWN = 1005, HOME_KEY = 1006, END_KEY = 1007, DEL_KEY = 1008;

    private static LibC.Termios originalAttributes;
    private static int rows = 10;
    private static int columns = 10;
    private static int cursorX = 0, offsetX = 0, cursorY = 0, offsetY = 0;
    private static List<String> content = List.of();

    public static void main(String[] args) throws IOException {

        openFile(args);

        enableRawMode();
        initEditor();

        while (true) {
            scroll();
            refreshScreen();
            int key = readKey();
            handleKey(key);
        }
    }

    private static void scroll() {
        if (cursorY >= rows + offsetY) {
            offsetY = cursorY - rows + 1;
        } else if (cursorY < offsetY) {
            offsetY = cursorY;
        }

        if (cursorX >= columns + offsetX) {
            offsetX = cursorX - columns + 1;
        } else if (cursorX < offsetX) {
            offsetX = cursorX;
        }
    }

    private static void openFile(String[] args) {
        if (args.length == 1) {
            String fileName = args[0];
            Path path = Path.of(fileName);
            if (Files.exists(path)) {
                try (Stream<String> stream = Files.lines(path)) {
                    content = stream.toList();
                } catch (IOException e) {
                    // TODO
                }
            }
        }
    }

    private static void initEditor() {
        LibC.Winsize windowSize = getWindowSize();
        rows = windowSize.ws_row - 1;
        columns = windowSize.ws_col;
    }

    private static void refreshScreen() {
        StringBuilder builder = new StringBuilder();

        moveCursorToTopLeft(builder);
        drawContent(builder);
        drawStatusBar(builder);
        drawCursor(builder);

        System.out.println(builder);
    }

    private static void moveCursorToTopLeft(StringBuilder builder) {
        builder.append("\033[H");
    }

    private static void drawCursor(StringBuilder builder) {
        builder.append(String.format("\033[%d;%dH", cursorY - offsetY + 1, cursorX - offsetX + 1));
    }

    private static void drawStatusBar(StringBuilder builder) {
//        String statusMessage = "Rows: " + rows + "X: " + cursorX + "Y: " + cursorY;
        String statusMessage = "Paytakov's Java text Editor - v0.0.1";
        builder.append("\033[7m")
                .append(statusMessage)
                .append(" ".repeat(Math.max(0, columns - statusMessage.length())))
                .append("\033[0m");
    }

    private static void drawContent(StringBuilder builder) {
        for (int i = 0; i < rows; i++) {
            int fileI = offsetY + i;

            if (fileI >= content.size()) {
                builder.append("~");
            } else {
                String currLine = content.get(fileI);
                int lengthToDrawForLine = currLine.length() - offsetX;

                if (lengthToDrawForLine < 0) {
                    lengthToDrawForLine = 0;
                }

                if (lengthToDrawForLine > columns) {
                    lengthToDrawForLine = columns;
                }

                if (lengthToDrawForLine > 0) {
                    builder.append(currLine,
                            offsetX,
                            offsetX + lengthToDrawForLine);
                }
            }
            builder.append("\r\n");
        }
    }

    private static void handleKey(int key) {
        if (key == 'q') {
            exit();
        } else if (List.of(
                ARROW_UP,
                ARROW_DOWN,
                ARROW_RIGHT,
                ARROW_LEFT,
                HOME_KEY,
                END_KEY,
                PAGE_UP,
                PAGE_DOWN).contains(key)) {

            moveCursor(key);
        }
    }

    private static void exit() {
        System.out.print("\033[2J");
        System.out.print("\033[H");
        LibC.INSTANCE.tcsetattr(
                LibC.SYSTEM_OUT_FD,
                LibC.TCSAFLUSH,
                originalAttributes);
        System.exit(0);
    }

    private static void moveCursor(int key) {
        String line = currentLine();
        switch (key) {
            case ARROW_UP -> {
                if (cursorY > 0) {
                    cursorY--;
                }
            }
            case ARROW_DOWN -> {
                if (cursorY < content.size()) {
                    cursorY++;
                }
            }
            case ARROW_RIGHT -> {
                if (line != null && cursorX < line.length()) {
                    cursorX++;
                }
            }
            case ARROW_LEFT -> {
                if (cursorX > 0) {
                    cursorX--;
                }
            }
            case PAGE_UP, PAGE_DOWN -> {

                if (key == PAGE_UP) {
                    moveCursorToTopOffScreen();
                } else if (key == PAGE_DOWN) {
                    moveCursorToBottomOffScreen();
                }

                for (int i = 0; i < rows; i++) {
                    moveCursor(key == PAGE_UP
                            ? ARROW_UP
                            : ARROW_DOWN);
                }
            }
            case HOME_KEY -> cursorX = 0;
            case END_KEY -> {
                if (line != null) {
                    cursorX = line.length();
                }
            }
        }

        String newLine = currentLine() ;
        if (newLine != null && cursorX > newLine.length()) {
            cursorX = newLine.length();
        }
    }

    private static String currentLine() {
        return cursorY < content.size()
                ? content.get(cursorY)
                : null;
    }

    private static void moveCursorToBottomOffScreen() {
        cursorY = offsetY + rows - 1;
        if (cursorY > content.size()) {
            cursorY = content.size();
        }
    }

    private static void moveCursorToTopOffScreen() {
        cursorY = offsetY;
    }

    private static int readKey() throws IOException {
        int key = System.in.read();
        if (key != '\033') {
            return key;
        }

        int nextKey = System.in.read();
        if (nextKey != '[' && nextKey != 'O') {
            return nextKey;
        }

        int yetAnotherKey = System.in.read();

        if (nextKey != '[') {
            return switch (yetAnotherKey) {
                case 'A' -> ARROW_UP;
                case 'B' -> ARROW_DOWN;
                case 'C' -> ARROW_RIGHT;
                case 'D' -> ARROW_LEFT;
                case 'H' -> HOME_KEY;
                case 'F' -> END_KEY;
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> { // e.g: esc[5~ == page_up
                    int yetYetAnotherChar = System.in.read();
                    if (yetYetAnotherChar != '~') {
                        yield yetYetAnotherChar;
                    }
                    switch (yetAnotherKey) {
                        case '1':
                        case '7':
                            yield HOME_KEY;
                        case '3':
                            yield DEL_KEY;
                        case '4':
                        case '8':
                            yield END_KEY;
                        case '5':
                            yield PAGE_UP;
                        case '6':
                            yield PAGE_DOWN;
                        default:
                            yield yetAnotherKey;
                    }
                }
                default -> yetAnotherKey;
            };
        } else { // if nextKey == 'O' -> e.g: escpOH == HOME
            return switch (yetAnotherKey) {
                case 'H' -> HOME_KEY;
                case 'F' -> END_KEY;
                default -> yetAnotherKey;
            };
        }
    }

    private static void enableRawMode() {
        LibC.Termios termios = new LibC.Termios();
        int rc = LibC.INSTANCE.tcgetattr(LibC.SYSTEM_OUT_FD, termios);

        if (rc != 0) {
            System.err.println("There was a problem calling tcgetattr");
            System.exit(rc);
        }

        originalAttributes = LibC.Termios.of(termios);

        termios.c_lflag &= ~(LibC.ECHO | LibC.ICANON | LibC.IEXTEN | LibC.ISIG);
        termios.c_iflag &= ~(LibC.IXON | LibC.ICRNL);
        termios.c_oflag &= ~(LibC.OPOST);

        termios.c_cc[LibC.VMIN] = 0;
        termios.c_cc[LibC.VTIME] = 1;

        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, termios);
    }

    private static LibC.Winsize getWindowSize() {
        final LibC.Winsize winsize = new LibC.Winsize();
        final int rc = LibC.INSTANCE.ioctl(
                LibC.SYSTEM_OUT_FD,
                LibC.TIOCGWINSZ, winsize);

        if (rc != 0) {
            System.err.println("ioctl failed with return code [{}]" + rc);
            System.exit(1);
        }

        return winsize;
    }
}

interface LibC extends Library {

    int SYSTEM_OUT_FD = 0;
    int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2,
            IXON = 2000, ICRNL = 400, IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x5413;

    // loading the C standard library for POSIX systems
    LibC INSTANCE = Native.load("c", LibC.class);

    @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
    class Winsize extends Structure {

        public short ws_row, ws_col, ws_xpixel, ws_ypixel;

    }

    @Structure.FieldOrder(value = {"c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc"})
    class Termios extends Structure {

        public int c_iflag, c_oflag, c_cflag, c_lflag;

        public byte[] c_cc = new byte[19];

        public Termios() {
        }

        public static Termios of(Termios t) {
            Termios copy = new Termios();
            copy.c_iflag = t.c_iflag;
            copy.c_oflag = t.c_oflag;
            copy.c_cflag = t.c_cflag;
            copy.c_lflag = t.c_lflag;
            copy.c_cc = t.c_cc.clone();
            return copy;
        }

        @Override
        public String toString() {
            return "Termios{" +
                    "c_iflag=" + c_iflag +
                    ", c_oflag=" + c_oflag +
                    ", c_cflag=" + c_cflag +
                    ", c_lflag=" + c_lflag +
                    ", c_cc=" + Arrays.toString(c_cc) +
                    '}';
        }
    }

    int tcgetattr(int fd, Termios termios);

    int tcsetattr(int fd, int optional_actions, Termios termios);

    int ioctl(int fd, int opt, Winsize winsize);
}

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

// usage: java Main [dataFolder] [--echo]
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean echo = false;
        Path dataDir = Path.of("data");
        for (String arg : args) {
            if (arg.equals("--echo")) {
                echo = true;
            } else {
                dataDir = Path.of(arg);
            }
        }
        AppServices services = AppServices.create(Clock.systemDefaultZone(), dataDir);
        ConsoleInput input = new ConsoleInput(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out, echo);
        new ConsoleMenu(input, System.out, services).run();
    }
}

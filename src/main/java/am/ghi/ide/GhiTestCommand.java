package am.ghi.ide;

import com.intellij.execution.configurations.GeneralCommandLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validated arguments for one-shot Ghi test and benchmark runs. */
final class GhiTestCommand {
    private static final Pattern DURATION=Pattern.compile("(?:\\d+(?:\\.\\d+)?(?:ns|us|µs|μs|ms|s|m|h))+");
    private static final Pattern ITERATIONS=Pattern.compile("[1-9]\\d*x");
    static void validateBenchmark(String pattern,String time,String count){
        if(pattern==null||pattern.isBlank())throw new IllegalArgumentException("Benchmark name regex must not be blank.");
        try{Pattern.compile(pattern);}catch(PatternSyntaxException error){throw new IllegalArgumentException("Invalid benchmark name regex: "+error.getDescription(),error);}
        if(time==null||!ITERATIONS.matcher(time.trim()).matches()&&!DURATION.matcher(time.trim()).matches())
            throw new IllegalArgumentException("Benchmark time must be a duration (1s) or positive iteration count (100x).");
        if(!ITERATIONS.matcher(time.trim()).matches()&&!time.matches(".*[1-9].*"))
            throw new IllegalArgumentException("Benchmark duration must be positive.");
        positive(count);
    }
    private static int positive(String count){
        try{int value=Integer.parseInt(count.trim());if(value>0)return value;}catch(RuntimeException ignored){}
        throw new IllegalArgumentException("Benchmark repetitions must be a positive integer.");
    }
    static GeneralCommandLine race(String executable,Path root){return command(executable,root,List.of("--race"));}
    static GeneralCommandLine benchmark(String executable,Path root,GhiSettings.State settings){
        validateBenchmark(settings.benchmarkPattern,settings.benchmarkTime,settings.benchmarkCount);
        var arguments=new ArrayList<>(List.of("--bench",settings.benchmarkPattern.trim(),"--benchtime",settings.benchmarkTime.trim(),"--count",Integer.toString(positive(settings.benchmarkCount))));
        if(settings.benchmarkMemory)arguments.add("--benchmem");
        return command(executable,root,arguments);
    }
    private static GeneralCommandLine command(String executable,Path root,List<String> flags){
        var arguments=new ArrayList<String>();arguments.add(executable);arguments.add("test");arguments.addAll(flags);arguments.add(root.toString());
        return new GeneralCommandLine(arguments).withWorkDirectory(root.toFile()).withCharset(StandardCharsets.UTF_8);
    }
}

package am.ghi.ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** Ghi's set-mode statement-start profile; coordinates are one-based UTF-8 byte columns. */
final class GhiCoverageProfile {
    private static final Pattern ROW=Pattern.compile("^(.+):(\\d+)\\.(\\d+),(\\d+)\\.(\\d+) 1 ([01])$");
    private static final Set<String> EXCLUDED=Set.of("tests","bin","vendor","node_modules");
    record Statement(int line,int offset,int endOffset,boolean covered) {}
    record FileCoverage(Path file,String source,List<Statement> statements) {
        long covered(){return statements.stream().filter(Statement::covered).count();}
        String percentage(){return statements.isEmpty()?"n/a":String.format(Locale.ROOT,"%.1f%%",100.0*covered()/statements.size());}
    }
    record Snapshot(Path root,Map<Path,String> sources) {}
    final Snapshot snapshot;
    final List<FileCoverage> files;
    private GhiCoverageProfile(Snapshot snapshot,List<FileCoverage> files){this.snapshot=snapshot;this.files=List.copyOf(files);}

    static String normalized(String source){return source.replace("\r\n","\n").replace('\r','\n');}
    static Snapshot snapshot(Path directory) throws IOException {
        Path root=directory.toRealPath();Map<Path,String> sources=new TreeMap<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs){
                if(!dir.equals(root)&&(dir.getFileName().toString().startsWith(".")||EXCLUDED.contains(dir.getFileName().toString())
                    ||Files.exists(dir.resolve("mojave.json"))||Files.exists(dir.resolve("mojave.lock"))))return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if(attrs.isRegularFile()&&!attrs.isSymbolicLink()&&file.toString().endsWith(".ghi"))sources.put(file,normalized(Files.readString(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        return new Snapshot(root,Map.copyOf(sources));
    }
    static GhiCoverageProfile read(Path profile,Snapshot snapshot) throws IOException {
        if(!Files.isRegularFile(profile,LinkOption.NOFOLLOW_LINKS))throw new IOException("No fresh coverage profile was produced.");
        if(Files.size(profile)>32*1024*1024)throw new IOException("Coverage profile is too large.");
        return parse(Files.readString(profile),snapshot);
    }
    static GhiCoverageProfile parse(String text,Snapshot snapshot) throws IOException {
        String[] rows=text.split("\n",-1);
        if(rows.length==0||!rows[0].stripTrailing().equals("mode: set"))throw new IOException("Expected Ghi statement coverage in mode: set.");
        Map<Path,List<Statement>> grouped=new TreeMap<>();Set<String> seen=new HashSet<>();
        for(Path file:snapshot.sources().keySet())grouped.put(file,new ArrayList<>());
        try{
            for(int i=1;i<rows.length;i++){
                String row=rows[i].stripTrailing();if(row.isEmpty()&&i==rows.length-1)continue;
                var match=ROW.matcher(row);if(!match.matches())throw new IOException("Invalid coverage record at line "+(i+1));
                String name=match.group(1).replace('\\','/');Path relative=Path.of(name);
                if(relative.isAbsolute()||name.contains(":"))throw new IOException("Coverage paths must be project-relative.");
                for(Path part:relative)if(part.toString().equals("..")||part.toString().equals("."))throw new IOException("Coverage path escapes the project.");
                Path file=snapshot.root().resolve(relative).normalize();String source=snapshot.sources().get(file);
                if(source==null)throw new IOException("Coverage references an excluded or unknown source: "+name);
                int line=Integer.parseInt(match.group(2)),column=Integer.parseInt(match.group(3));
                if(line!=Integer.parseInt(match.group(4))||column<=0||column==Integer.MAX_VALUE||Integer.parseInt(match.group(5))!=column+1)
                    throw new IOException("Expected a one-column statement-start span.");
                int offset=byteOffset(source,line,column);
                if(!seen.add(file+":"+offset))throw new IOException("Duplicate statement coverage record.");
                grouped.get(file).add(new Statement(line-1,offset,offset+Character.charCount(source.codePointAt(offset)),match.group(6).equals("1")));
            }
        }catch(IllegalArgumentException|IndexOutOfBoundsException error){throw new IOException("Invalid coverage coordinates or path.",error);}
        var files=new ArrayList<FileCoverage>();
        grouped.forEach((file,statements)->{statements.sort(Comparator.comparingInt(Statement::offset));files.add(new FileCoverage(file,snapshot.sources().get(file),List.copyOf(statements)));});
        return new GhiCoverageProfile(snapshot,files);
    }
    private static int byteOffset(String source,int line,int column) throws IOException {
        if(line<=0)throw new IOException("Invalid coverage line.");
        int start=0;
        for(int at=1;at<line;at++){int next=source.indexOf('\n',start);if(next<0)throw new IOException("Coverage line exceeds the source.");start=next+1;}
        int end=source.indexOf('\n',start);if(end<0)end=source.length();
        int bytes=1;
        for(int offset=start;offset<end;){
            if(bytes==column)return offset;
            int width=Character.charCount(source.codePointAt(offset));
            bytes+=source.substring(offset,offset+width).getBytes(StandardCharsets.UTF_8).length;offset+=width;
            if(bytes>column)throw new IOException("Coverage column splits a UTF-8 character.");
        }
        throw new IOException("Coverage column exceeds the source.");
    }
    boolean unchanged() throws IOException {return snapshot.sources().equals(snapshot(snapshot.root()).sources());}
}

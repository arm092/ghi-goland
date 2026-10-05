package am.ghi.ide;

import com.google.gson.*;
import com.intellij.model.Pointer;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.backend.documentation.*;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Compiler-backed expression types. A request never reuses a result from an older project state. */
public final class GhiTypeDocumentation implements DocumentationTargetProvider {
    private static final long TIMEOUT_NANOS=TimeUnit.SECONDS.toNanos(30);
    private static final Map<Project,Process> ACTIVE=new ConcurrentHashMap<>();

    @Override public @NotNull List<? extends DocumentationTarget> documentationTargets(@NotNull PsiFile file,int offset){
        if(file.getLanguage()!=GhiLanguage.INSTANCE||offset<0||offset>=file.getTextLength())return List.of();
        var element=file.findElementAt(offset);
        if(element==null||element.getNode().getElementType()==com.intellij.psi.TokenType.WHITE_SPACE
            ||element.getNode().getElementType()==GhiLexer.COMMENT)return List.of();
        Snapshot snapshot=Snapshot.capture(file);
        return snapshot==null?List.of():List.of(new TypeTarget(snapshot,offset));
    }

    static final class TypeTarget implements DocumentationTarget {
        private final Snapshot snapshot;
        private final int offset;
        TypeTarget(Snapshot snapshot,int offset){this.snapshot=snapshot;this.offset=offset;}
        @Override public @NotNull Pointer<? extends DocumentationTarget> createPointer(){
            return ()->snapshot.isCurrent()?new TypeTarget(snapshot,offset):null;
        }
        @Override public @NotNull TargetPresentation computePresentation(){
            return TargetPresentation.builder("Ghi expression type").presentation();
        }
        @Override public DocumentationResult computeDocumentation(){
            return DocumentationResult.asyncDocumentation(()->{
                String type=analyze(snapshot,offset);
                if(type==null)return null;
                String safe=com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(type);
                return DocumentationResult.documentation("<div class='definition'><code>"+safe+"</code></div>");
            });
        }
    }

    static final class Snapshot {
        final PsiFile file;
        final Project project;
        final Document document;
        final Path path,directory;
        final String executable,text,sha;
        final long documentStamp;
        private Snapshot(PsiFile file,Document document,Path path,Path directory,String executable,
                         String text){
            this.file=file;this.project=file.getProject();this.document=document;this.path=path;
            this.directory=directory;this.executable=executable;this.text=text;this.sha=sha(text);
            this.documentStamp=document.getModificationStamp();
        }
        static Snapshot capture(PsiFile file){
            VirtualFile virtual=file.getVirtualFile();
            if(virtual==null||!virtual.isInLocalFileSystem())return null;
            Document document=file.getViewProvider().getDocument();if(document==null)return null;
            try{
                Path path=Path.of(virtual.getPath()).toAbsolutePath().normalize();
                if(!Files.isRegularFile(path)||GhiDependencies.installed(file))return null;
                var settings=GhiSettings.get(file.getProject()).getState();
                Path directory=GhiCommand.directory(file.getProject().getBasePath(),settings.directory);
                if(!path.startsWith(directory)||path.startsWith(directory.resolve("tests"))||!Files.isDirectory(directory))return null;
                if(otherUnsaved(directory,document))return null;
                return new Snapshot(file,document,path,directory,GhiCommand.executable(settings.executable),
                    document.getText());
            }catch(IllegalArgumentException|NullPointerException error){return null;}
        }
        boolean isCurrent(){
            if(ApplicationManager.getApplication().isReadAccessAllowed())return currentState();
            try{return ReadAction.computeCancellable(this::currentState);}
            catch(ReadAction.CannotReadException ignored){return false;}
        }
        private boolean currentState(){
            if(project.isDisposed()||!file.isValid()||document.getModificationStamp()!=documentStamp
                ||otherUnsaved(directory,document))return false;
            try{
                var settings=GhiSettings.get(project).getState();
                return path.equals(Path.of(file.getVirtualFile().getPath()).toAbsolutePath().normalize())
                    &&directory.equals(GhiCommand.directory(project.getBasePath(),settings.directory))
                    &&executable.equals(GhiCommand.executable(settings.executable));
            }catch(IllegalArgumentException|NullPointerException error){return false;}
        }
    }

    private static boolean otherUnsaved(Path directory,Document current){
        var manager=FileDocumentManager.getInstance();
        for(Document document:manager.getUnsavedDocuments()){
            if(document==current)continue;
            VirtualFile file=manager.getFile(document);if(file==null||!file.isInLocalFileSystem())continue;
            try{if(Path.of(file.getPath()).toAbsolutePath().normalize().startsWith(directory))return true;}
            catch(InvalidPathException ignored){return true;}
        }
        return false;
    }
    private static String sha(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    static String analyze(Snapshot snapshot,int editorOffset){
        if(!snapshot.isCurrent())return null;
        Map<String,FileStamp> projectState=projectState(snapshot.directory);
        if(projectState==null||!snapshot.isCurrent())return null;
        String json=runProcess(snapshot.project,new ProcessBuilder(snapshot.executable,"analyze","--json","--types","--project",snapshot.directory.toString(),
            "--stdin","--filename",snapshot.path.toString()).directory(snapshot.directory.toFile()),
            snapshot.text.getBytes(StandardCharsets.UTF_8),snapshot::isCurrent,TIMEOUT_NANOS);
        if(json==null)return null;
        String type=typeAt(json,snapshot.path,snapshot.sha,snapshot.text,editorOffset);
        return snapshot.isCurrent()&&projectState.equals(projectState(snapshot.directory))?type:null;
    }
    private record FileStamp(long size,long modifiedNanos) {}
    private static Map<String,FileStamp> projectState(Path directory){
        Map<String,FileStamp> result=new TreeMap<>();
        try{
            Files.walkFileTree(directory,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes){
                    if(!path.equals(directory)&&Set.of(".git",".work","build","bin","node_modules").contains(path.getFileName().toString()))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes){
                    String name=path.getFileName().toString();
                    if(name.endsWith(".ghi")||name.endsWith(".go")||Set.of("mojave.lock","mojave.json","go.mod","go.sum","go.work","go.work.sum").contains(name))
                        result.put(directory.relativize(path).toString(),new FileStamp(attributes.size(),attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        }catch(IOException error){return null;}
    }
    static String runProcess(ProcessBuilder builder,byte[] input,BooleanSupplier current,long timeoutNanos){
        return runProcess(null,builder,input,current,timeoutNanos);
    }
    private static String runProcess(Project project,ProcessBuilder builder,byte[] input,BooleanSupplier current,long timeoutNanos){
        Process process=null;
        try{
            process=builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(project!=null){Process previous=ACTIVE.put(project,process);if(previous!=null&&previous.isAlive())terminateTree(previous);}
            Process running=process;
            CompletableFuture<byte[]> output=CompletableFuture.supplyAsync(()->{
                try(var stream=running.getInputStream()){return stream.readAllBytes();}
                catch(IOException error){return new byte[0];}
            });
            CompletableFuture<Void> writer=CompletableFuture.runAsync(()->{
                try(var stream=running.getOutputStream()){stream.write(input);}
                catch(IOException error){throw new java.io.UncheckedIOException(error);}
            });
            long deadline=System.nanoTime()+timeoutNanos;
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)){
                ProgressManager.checkCanceled();
                if(writer.isCompletedExceptionally()||!current.getAsBoolean()||System.nanoTime()>=deadline)return null;
            }
            if(process.exitValue()!=0||!current.getAsBoolean()||System.nanoTime()>=deadline)return null;
            writer.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            return new String(output.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS),StandardCharsets.UTF_8);
        }catch(IOException|InterruptedException|ExecutionException|TimeoutException error){
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            return null;
        }finally{
            if(project!=null&&process!=null)ACTIVE.remove(project,process);
            if(process!=null&&process.isAlive())terminateTree(process);
        }
    }
    private static void terminateTree(Process process){
        List<ProcessHandle> children=process.toHandle().descendants().toList();
        for(int i=children.size()-1;i>=0;i--)children.get(i).destroyForcibly();
        process.destroyForcibly();
    }
    static String typeAt(String json,Path path,String sha,String source,int editorOffset){
        if(editorOffset<0||editorOffset>=source.length())return null;
        try{
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            if(root.get("schemaVersion").getAsInt()!=1||!sha.equals(root.get("sha256").getAsString())
                ||!path.equals(Path.of(root.get("filename").getAsString()).toAbsolutePath().normalize()))return null;
            JsonArray diagnostics=root.getAsJsonArray("diagnostics");
            JsonArray capabilities=root.getAsJsonArray("capabilities");
            if(diagnostics==null||!diagnostics.isEmpty()||capabilities==null)return null;
            boolean enabled=false;
            for(JsonElement capability:capabilities)if("expressionTypes".equals(capability.getAsString()))enabled=true;
            if(!enabled)return null;
            JsonArray entries=root.getAsJsonArray("expressionTypes");if(entries==null)return null;
            int byteOffset=utf8Offset(source,editorOffset),byteLength=source.getBytes(StandardCharsets.UTF_8).length;
            int bestWidth=Integer.MAX_VALUE;String type=null;
            for(JsonElement entry:entries){
                JsonObject value=entry.getAsJsonObject();
                int start=value.get("start").getAsInt(),end=value.get("end").getAsInt();
                String candidate=value.get("type").getAsString();
                if(start<0||end<=start||end>byteLength||candidate.isBlank())return null;
                if(start<=byteOffset&&byteOffset<end&&end-start<bestWidth){bestWidth=end-start;type=candidate;}
            }
            return type;
        }catch(JsonParseException|IllegalStateException|NullPointerException|NumberFormatException|InvalidPathException error){return null;}
    }
    static int utf8Offset(String source,int editorOffset){
        int bytes=0;
        for(int at=0;at<editorOffset;){
            int codePoint=source.codePointAt(at),width=Character.charCount(codePoint);
            if(at+width>editorOffset)break;
            bytes+=codePoint<=0x7f?1:codePoint<=0x7ff?2:codePoint<=0xffff?3:4;
            at+=width;
        }
        return bytes;
    }
}

package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;

/** Official Termux userspace under this app's UID; JNI PRoot preserves target API 36. */
final class EmbeddedRuntime {
    static final String PREFIX="/data/data/com.termux/files/usr", HOME="/data/data/com.termux/files/home";
    private static EmbeddedRuntime instance;
    static synchronized EmbeddedRuntime get(Context c){if(instance==null)instance=new EmbeddedRuntime(c.getApplicationContext());return instance;}
    static void open(Context c){Intent i=new Intent(c,RuntimeActivity.class);if(!(c instanceof android.app.Activity))i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}
    private final Context app;
    private final File root, userHome;
    private final ExecutorService setup=Executors.newSingleThreadExecutor();
    private final Object initializationLock=new Object();
    // One slot per chat ChatHub lets work at once, plus headroom for runs still tearing down after Stop.
    private final ExecutorService helpers=Executors.newFixedThreadPool(ChatHub.MAX_RUNNING+2);
    /** "ask" requests by callback run id: true while queued or running, false once finished. */
    private final Map<String,Boolean> askRuns=new LinkedHashMap<>();
    private final ExecutorService controls=Executors.newFixedThreadPool(2);
    private final Set<Process> helperProcesses=Collections.synchronizedSet(new HashSet<>());
    private long helperEpoch;
    private volatile boolean busy,cancelled;
    private volatile int progress;
    private volatile String status="The environment is not prepared yet",error="";
    private volatile File current;
    private volatile Process configuring;
    private volatile HttpURLConnection downloading;
    private int jobs;
    private final JSONObject manifest, clients;
    private final String abi, revision;

    private EmbeddedRuntime(Context c){
        app=c;root=new File(c.getFilesDir(),"terminal");userHome=new File(root,"home");
        try{
            abi=selectAbi();manifest=assetJson("terminal/"+abi+"/manifest.json");clients=assetJson("terminal/clients-lock.json");
            revision=manifest.getString("sha256");current=new File(root,"runtime-"+revision.substring(0,16));
            if(ready()){status="Built-in terminal ready";progress=100;}
        }catch(Exception e){throw new IllegalStateException("No valid terminal environment for this ABI",e);}
    }
    private String selectAbi(){for(String value:Build.SUPPORTED_ABIS)if("arm64-v8a".equals(value)||"x86_64".equals(value))return value;throw new IllegalStateException("Requires ARM64 or x86_64 Android");}
    boolean ready(){return current!=null&&new File(current,".ready").isFile();}
    boolean busy(){return busy;}
    int progress(){return progress;}
    String status(){return status;}
    String error(){return error;}
    File home(){if(!userHome.isDirectory())userHome.mkdirs();return userHome;}
    File prefix(){return new File(current,"usr");}
    synchronized int activeJobs(){return jobs;}
    void bootstrap(){dispatch("bootstrap",null,null);}
    void prepare(){dispatch("prepare",null,null);}
    void installClient(String backend){requireBackend(backend);dispatch("install",backend,null);}
    void cancelSetup(){cancelled=true;HttpURLConnection connection=downloading;if(connection!=null)connection.disconnect();Process p=configuring;if(p!=null)p.destroyForcibly();}
    void emergencyStop(){cancelSetup();Process[] running;synchronized(helperProcesses){helperEpoch++;running=helperProcesses.toArray(new Process[0]);}for(Process p:running)if(p.isAlive())p.destroyForcibly();TerminalActivity.stopAll();}
    void refreshAccounts(){if(ready())runHelper("ping");}
    void runHelper(String...args){if(!ready())throw new IllegalStateException("The terminal environment is being prepared. Open setup.");dispatch("helper",null,args);}
    private void dispatch(String action,String backend,String[] args){
        if(!"helper".equals(action)&&busy)return;
        Intent i=new Intent(app,RuntimeService.class).setAction(action);if(backend!=null)i.putExtra("backend",backend);if(args!=null)i.putExtra("args",args);
        if("helper".equals(action))synchronized(helperProcesses){i.putExtra("helper_epoch",helperEpoch);}
        app.startForegroundService(i);
    }
    /** Liveness of one chat request's helper process (ChatHub's stall watchdog): TRUE queued or
     *  running, FALSE finished, null unknown (not dispatched here, or long forgotten). */
    Boolean askAlive(String run){if(run==null||run.isEmpty())return null;synchronized(askRuns){return askRuns.get(run);}}
    private void trackAsk(String run,boolean alive){
        if(run==null||run.isEmpty())return;
        synchronized(askRuns){askRuns.put(run,alive);Iterator<Map.Entry<String,Boolean>> old=askRuns.entrySet().iterator();while(askRuns.size()>64&&old.hasNext())if(!old.next().getValue())old.remove();}
    }
    void enqueue(Intent intent,Runnable idle){
        String action=intent.getAction();
        synchronized(this){if(!"helper".equals(action)&&busy)return;jobs++;if(!"helper".equals(action)){busy=true;cancelled=false;error="";}}
        String[] askArgs="helper".equals(action)?intent.getStringArrayExtra("args"):null;
        String askRun=askArgs!=null&&askArgs.length>8&&"ask".equals(askArgs[0])?askArgs[8]:null;
        trackAsk(askRun,true);
        Runnable work=()->{
            try{
                if("helper".equals(action))executeHelper(intent.getStringArrayExtra("args"),intent.getLongExtra("helper_epoch",-1));
                else{
                    initializeBlocking();
                    if("install".equals(action))installClientBlocking(intent.getStringExtra("backend"));
                    else if("prepare".equals(action)){
                        String wanted=Prefs.agents(app);
                        if("codex".equals(wanted)||"both".equals(wanted))installClientBlocking("codex");
                        if("claude".equals(wanted)||"both".equals(wanted))installClientBlocking("claude");
                    }
                    status="Built-in terminal ready";progress=100;
                    refreshAccounts();
                }
            }catch(Exception e){
                if(!"helper".equals(action)){error=cancelled?"Setup cancelled. You can retry it.":friendly(e);status="Setup did not finish";appendLog(error);}
            }finally{trackAsk(askRun,false);synchronized(this){jobs--;if(!"helper".equals(action))busy=false;}new Handler(Looper.getMainLooper()).post(idle);}
        };
        String[] helperArgs=intent.getStringArrayExtra("args");
        ("helper".equals(action)?(helperArgs!=null&&helperArgs.length>0&&"ask".equals(helperArgs[0])?helpers:controls):setup).execute(work);
    }
    private static String friendly(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    private void stage(String text,int percent){status=text;progress=percent;appendLog(text);if(cancelled)throw new CancellationException("Setup cancelled");}
    private void appendLog(String message){
        try{root.mkdirs();File f=new File(root,"setup.log");if(f.length()>128*1024)write(f,"",false);write(f,message+"\n",true);}catch(IOException ignored){}
    }
    String setupLog(){try{return read(new File(root,"setup.log"),128*1024);}catch(IOException e){return "No log";}}

    /** Blocking initialization is used only by the worker or instrumentation, never the UI. */
    void initializeBlocking()throws Exception{synchronized(initializationLock){initializeLocked();}}
    private void initializeLocked()throws Exception{
        root.mkdirs();home();
        if(ready()){installHelper();return;}
        stage("Checking built-in environment · "+abi,5);
        long archiveSize=manifest.getLong("bytes");
        if(root.getUsableSpace()<Math.max(1024L*1024*1024,archiveSize*5))throw new IOException("Not enough space: free up at least 1 GB.");
        File archive=new File(root,"runtime.zip.part"),staging=new File(root,"runtime-staging");
        remove(staging);staging.mkdirs();
        try{
            copyVerified(app.getAssets().open("terminal/"+abi+"/"+manifest.getString("archive")),archive,archiveSize,revision,"Copying environment",5,20);
            stage("Unpacking terminal tools",21);
            long extracted=0;
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))){
                ZipEntry entry;byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){
                    if(cancelled)throw new CancellationException();
                    File file=safeChild(staging,entry.getName());
                    if(entry.isDirectory()){if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Cannot create the environment directory");}
                    else{
                        file.getParentFile().mkdirs();
                        try(OutputStream output=new FileOutputStream(file)){int n;while((n=zip.read(buffer))!=-1){extracted+=n;if(extracted>2L*1024*1024*1024)throw new IOException("Environment archive exceeds the limit");output.write(buffer,0,n);}}
                        file.setReadable(true,true);file.setWritable(true,true);
                    }
                    zip.closeEntry();
                }
            }
            JSONArray executable=manifest.getJSONArray("executable_paths");
            for(int n=0;n<executable.length();n++)if(!safeChild(staging,executable.getString(n)).setExecutable(true,true))throw new IOException("Cannot prepare the terminal tool");
            JSONArray links=manifest.getJSONArray("symlinks");
            for(int n=0;n<links.length();n++){JSONObject link=links.getJSONObject(n);File path=safeChild(staging,link.getString("path"));path.getParentFile().mkdirs();Os.symlink(link.getString("target"),path.getPath());}
            if(current.exists())remove(current);
            if(!staging.renameTo(current))throw new IOException("Cannot save the terminal environment");
            installHelper();
            stage("First tool startup",45);
            File secondStage=new File(prefix(),"etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh");
            if(!secondStage.isFile())throw new IOException("Termux first-run script is missing");
            runBlocking(command(PREFIX+"/bin/bash",secondStage.getPath().replace(prefix().getPath(),PREFIX)),900);
            // Every acceptance probe runs through the actual signed-app JNI tracer.
            String proof=runBlocking(command(PREFIX+"/bin/bash","--noprofile","--norc","-c","set -eo pipefail; printf 'ANDROIDAGENT_RUNTIME_OK\\n'; bash --version; node --version; python --version; jq --version; curl --version; git --version"),180);
            if(!proof.contains("ANDROIDAGENT_RUNTIME_OK"))throw new IOException("The tools did not respond correctly");
            appendLog(proof);
            write(new File(current,".ready"),revision+"\n",false);
            Prefs.setHelperStatus(app,"none");Prefs.setSignedAgents(app,"none");Prefs.setHelperVersion(app,0);
            stage("Built-in terminal ready",100);
        }finally{archive.delete();remove(staging);}
    }
    private void installHelper()throws IOException{
        File bin=new File(home(),"bin"),work=new File(home(),".buddy/work");bin.mkdirs();work.mkdirs();
        for(String name:new String[]{"buddy-run","codex","claude"})copyAsset("termux/"+name,new File(bin,name),true);
        copyAsset("termux/system-prompt.md",new File(home(),".buddy/system-prompt.md"),false);
        File guidance=new File(work,"AGENTS.md");if(!guidance.exists())copyAsset("termux/AGENTS.md",guidance,false);
        for(String name:new String[]{"SKILL.md","build-apk.sh","setup-toolchain.sh"})copyAsset("termux/android-app/"+name,new File(work,".claude/skills/android-app/"+name),name.endsWith(".sh"));
    }
    private void copyAsset(String name,File destination,boolean executable)throws IOException{
        destination.getParentFile().mkdirs();File temporary=new File(destination.getPath()+".new");
        try(InputStream in=app.getAssets().open(name);OutputStream out=new FileOutputStream(temporary)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        temporary.setExecutable(executable,true);if(!temporary.renameTo(destination))throw new IOException("Cannot update the tool bridge");
    }
    boolean agentInstalled(String backend){
        try{File folder=clientDirectory(backend);return verifiedClientDirectory(backend)&&new File(home(),".androidagent/clients/"+backend).getCanonicalPath().equals(folder.getCanonicalPath());}catch(Exception e){return false;}
    }
    private boolean verifiedClientDirectory(String backend)throws Exception{JSONObject lock=clientLock(backend);File folder=clientDirectory(backend);return new File(folder,".verified").isFile()&&read(new File(folder,".verified"),256).trim().equals(lock.getString("sha256"))&&new File(folder,lock.getString("binary")).isFile();}
    private void repairClientLink(String backend,File destination)throws Exception{File link=new File(home(),".androidagent/clients/"+backend),temporary=new File(link.getPath()+".new");temporary.delete();Os.symlink(destination.getName(),temporary.getPath());Os.rename(temporary.getPath(),link.getPath());}
    private static void requireBackend(String value){if(!"codex".equals(value)&&!"claude".equals(value))throw new IllegalArgumentException("Unknown client");}
    private JSONObject clientLock(String backend)throws JSONException{requireBackend(backend);return clients.getJSONObject(backend).getJSONObject("targets").getJSONObject(abi);}
    private File clientDirectory(String backend)throws JSONException{return new File(home(),".androidagent/clients/"+backend+"-"+clients.getJSONObject(backend).getString("version"));}
    void installClientBlocking(String backend)throws Exception{
        requireBackend(backend);if(!ready())initializeBlocking();if(verifiedClientDirectory(backend)){repairClientLink(backend,clientDirectory(backend));return;}
        JSONObject lock=clientLock(backend);File destination=clientDirectory(backend),staging=new File(destination.getPath()+".staging"),archive=new File(root,backend+".tgz.part");
        if(root.getUsableSpace()<lock.getLong("bytes")*4+128L*1024*1024)throw new IOException("Not enough space for client "+backend+". Free up at least 1 GB.");
        stage("Downloading official client "+backend,50);remove(staging);staging.mkdirs();
        try{
            URL url=new URL(lock.getString("url"));if(!"https".equals(url.getProtocol())||!"registry.npmjs.org".equals(url.getHost()))throw new IOException("Invalid official client source");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(30000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            downloading=connection;
            try{if(cancelled)throw new CancellationException();int code=connection.getResponseCode();if(code!=200)throw new IOException("Downloading "+backend+": HTTP "+code);copyVerified(connection.getInputStream(),archive,lock.getLong("bytes"),lock.getString("sha256"),"Downloading "+backend,50,85);}finally{downloading=null;connection.disconnect();}
            installClientArchive(backend,archive,staging,destination,lock);
        }finally{archive.delete();remove(staging);}
    }
    /** Package-private verified archive seam: test download failure and the real pinned clients without account login. */
    void installVerifiedClientArchive(String backend,File archive)throws Exception{
        JSONObject lock=clientLock(backend);File destination=clientDirectory(backend),staging=new File(destination.getPath()+".staging");
        if(archive.length()!=lock.getLong("bytes")||!hash(archive).equals(lock.getString("sha256")))throw new IOException("Invalid client checksum");
        remove(staging);staging.mkdirs();try{installClientArchive(backend,archive,staging,destination,lock);}finally{remove(staging);}
    }
    private void installClientArchive(String backend,File archive,File staging,File destination,JSONObject lock)throws Exception{
        stage("Installing "+backend+" and checking the first launch",90);
        runBlocking(command(PREFIX+"/bin/tar","--no-same-owner","-xzf",archive.getPath(),"-C",staging.getPath()),600);
        File binary=safeChild(staging,lock.getString("binary"));if(!binary.isFile())throw new IOException("The archive contains no client for "+abi);binary.setExecutable(true,true);
        String version=runBlocking(command(binary.getPath(),"--version"),180);
        if(!version.contains(clients.getJSONObject(backend).getString("version")))throw new IOException("Client did not confirm the expected version: "+version.trim());
        appendLog(version.trim());write(new File(staging,".verified"),lock.getString("sha256")+"\n",false);
        if(destination.exists())remove(destination);
        if(!staging.renameTo(destination))throw new IOException("Cannot save client "+backend);
        repairClientLink(backend,destination);
    }
    ProcessBuilder command(String...args)throws Exception{
        if(!new File(prefix(),"bin/bash").isFile())throw new IOException("The environment is still being prepared");
        String nativeDir=app.getApplicationInfo().nativeLibraryDir;JSONObject executables=manifest.getJSONObject("executables");
        File temp=new File(root,"tmp");temp.mkdirs();File resolver=new File(root,"resolv.conf");refreshDns(resolver);
        List<String> full=new ArrayList<>();full.add(new File(nativeDir,executables.getString("proot")).getPath());full.add("--kill-on-exit");
        Collections.addAll(full,"-b",prefix().getPath()+":"+PREFIX,"-b",home().getPath()+":"+HOME,"-b",new File(prefix(),"bin").getPath()+":/bin","-b",new File(prefix(),"bin").getPath()+":/usr/bin","-b",new File(prefix(),"opt/androidagent/musl/lib").getPath()+":/lib","-b",resolver.getPath()+":/etc/resolv.conf","-b",new File(prefix(),"etc/tls").getPath()+":/etc/ssl","-w",HOME);Collections.addAll(full,args);
        ProcessBuilder pb=new ProcessBuilder(full);pb.directory(home());pb.redirectErrorStream(true);
        Map<String,String> env=pb.environment();env.remove("LD_PRELOAD");env.remove("BUN_OPTIONS");
        // Keep Termux's unmodified libraries ahead of QEMU's separately renamed
        // JNI copies. The parent PRoot process still resolves its prefixed
        // libaaterm_* dependencies in nativeDir before the PRoot binds exist.
        env.put("LD_LIBRARY_PATH",PREFIX+"/lib:"+nativeDir);
        env.put("PROOT_LOADER",new File(nativeDir,executables.getString("loader")).getPath());env.put("PROOT_TMP_DIR",temp.getPath());
        env.put("HOME",HOME);env.put("PREFIX",PREFIX);env.put("PATH",HOME+"/bin:"+PREFIX+"/bin:/system/bin");env.put("TMPDIR",PREFIX+"/tmp");env.put("SHELL",PREFIX+"/bin/bash");env.put("TERM","xterm-256color");env.put("LANG","C.UTF-8");
        env.put("SSL_CERT_FILE",PREFIX+"/etc/tls/cert.pem");env.put("SSL_CERT_DIR",PREFIX+"/etc/tls/certs");env.put("DISABLE_AUTOUPDATER","1");env.put("ANDROIDAGENT_EMBEDDED","1");env.put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE","disable");
        env.put("ANDROID__BUILD_VERSION_SDK",Integer.toString(Build.VERSION.SDK_INT));env.put("TERMUX__UID",Integer.toString(android.os.Process.myUid()));
        return pb;
    }
    private synchronized void refreshDns(File file)throws IOException{
        StringBuilder dns=new StringBuilder();try{ConnectivityManager cm=app.getSystemService(ConnectivityManager.class);LinkProperties p=cm.getLinkProperties(cm.getActiveNetwork());if(p!=null)for(InetAddress address:p.getDnsServers())dns.append("nameserver ").append(address.getHostAddress()).append('\n');}catch(Exception ignored){}
        if(dns.length()==0){File original=new File(prefix(),"etc/resolv.conf");dns.append(original.isFile()?read(original,8192):"nameserver 1.1.1.1\nnameserver 8.8.8.8\n");}dns.append("options timeout:3 attempts:2\n");File temporary=new File(file.getPath()+".new");write(temporary,dns.toString(),false);if(!temporary.renameTo(file))throw new IOException("Cannot update the environment DNS");
    }
    /** Owner-approved background service (ServiceManager): its own PRoot tree, never in helperProcesses, so neither a run's Stop nor emergencyStop ends it. */
    ProcessBuilder serviceCommand(String script,String...args)throws Exception{
        if(!ready())throw new IllegalStateException("The environment is not prepared");
        List<String> full=new ArrayList<>();Collections.addAll(full,PREFIX+"/bin/bash","--noprofile","--norc","-c",script,"androidagent-service");Collections.addAll(full,args);
        return command(full.toArray(new String[0]));
    }
    ProcessBuilder interactiveCommand(String...args)throws Exception{
        StringBuilder shell=new StringBuilder("exec");for(String arg:args)shell.append(" '").append(arg.replace("'","'\\''")).append("'");
        return command(PREFIX+"/bin/script","--quiet","--return","--flush","--echo","never","--command",shell.toString(),"/dev/null");
    }
    private void executeHelper(String[] args,long epoch)throws Exception{
        if(args==null||args.length==0)throw new IllegalArgumentException("No bridge command");
        List<String> full=new ArrayList<>();Collections.addAll(full,PREFIX+"/bin/bash",HOME+"/bin/buddy-run",Prefs.token(app));Collections.addAll(full,args);
        ProcessBuilder builder=command(full.toArray(new String[0]));Process process;
        synchronized(helperProcesses){if(epoch!=helperEpoch)throw new CancellationException("Command canceled by Stop");process=RuntimeProcess.start(builder);helperProcesses.add(process);}
        process.getOutputStream().close();
        try(InputStream output=process.getInputStream()){byte[] buffer=new byte[8192];while(output.read(buffer)!=-1){}process.waitFor();}finally{helperProcesses.remove(process);if(process.isAlive())process.destroyForcibly();}
    }
    String runBlocking(ProcessBuilder builder,int seconds)throws Exception{
        Process process=RuntimeProcess.start(builder);configuring=process;if(cancelled)process.destroyForcibly();ByteArrayOutputStream bytes=new ByteArrayOutputStream();process.getOutputStream().close();
        Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){synchronized(bytes){int take=Math.min(n,32768-bytes.size());if(take>0)bytes.write(buffer,0,take);}}}catch(IOException ignored){}},"runtime-output");reader.setDaemon(true);reader.start();
        try{if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Tool exceeded the time limit of "+seconds+" s");}reader.join(2000);String output;synchronized(bytes){output=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}if(process.exitValue()!=0)throw new IOException("Tool exited with code "+process.exitValue()+": "+output);return output;}finally{if(process.isAlive())process.destroyForcibly();configuring=null;}
    }
    JSONObject diagnostics()throws JSONException{return new JSONObject().put("abi",abi).put("target_sdk",app.getApplicationInfo().targetSdkVersion).put("ready",ready()).put("runtime_sha256",revision).put("codex_installed",agentInstalled("codex")).put("claude_installed",agentInstalled("claude")).put("external_termux_required",false);}
    private JSONObject assetJson(String path)throws IOException,JSONException{try(InputStream in=app.getAssets().open(path)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Manifest is too large");out.write(b,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}}
    private void copyVerified(InputStream input,File file,long expected,String digest,String label,int start,int end)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");long count=0;byte[] b=new byte[65536];
        try(InputStream in=input;OutputStream out=new FileOutputStream(file)){int n;while((n=in.read(b))!=-1){if(cancelled)throw new CancellationException();count+=n;if(count>expected)throw new IOException("Downloaded file is too large");sha.update(b,0,n);out.write(b,0,n);progress=start+(int)((end-start)*count/expected);status=label+" · "+(count/1048576)+" / "+(expected/1048576)+" MB";}}
        if(count!=expected||!hex(sha.digest()).equals(digest))throw new IOException("SHA256 checksum of the downloaded file does not match");
    }
    private static File safeChild(File parent,String path)throws IOException{if(path.startsWith("/")||path.indexOf('\0')>=0)throw new IOException("Invalid archive path");File file=new File(parent,path);if(!file.getCanonicalPath().startsWith(parent.getCanonicalPath()+File.separator))throw new IOException("Path outside the environment directory");return file;}
    private static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return hex(digest.digest());}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte value:b)s.append(String.format(Locale.ROOT,"%02x",value&255));return s.toString();}
    private static String read(File file,int limit)throws IOException{try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b,0,Math.min(b.length,limit-out.size())))>0)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static void write(File file,String value,boolean append)throws IOException{file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file,append)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static void remove(File file)throws IOException{if(!file.exists()&&!java.nio.file.Files.isSymbolicLink(file.toPath()))return;if(file.isDirectory()&&!java.nio.file.Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}if(!file.delete())throw new IOException("Cannot delete the temporary environment file");}
}

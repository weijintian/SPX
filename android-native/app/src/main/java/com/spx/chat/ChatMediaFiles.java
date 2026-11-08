package com.spx.chat;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Cross-thread leases protect active playback and cache seeding from manual cleanup. */
final class ChatMediaFiles {
    private static final Map<String,Integer> ACTIVE=new HashMap<>();
    static final class Lease implements AutoCloseable {
        private String path;
        private Lease(String path){this.path=path;}
        public void close(){synchronized(ACTIVE){if(path==null)return;int n=ACTIVE.get(path)-1;if(n==0)ACTIVE.remove(path);else ACTIVE.put(path,n);path=null;}}
    }
    static Lease protect(File file){synchronized(ACTIVE){String path=file.getAbsolutePath();ACTIVE.put(path,ACTIVE.getOrDefault(path,0)+1);return new Lease(path);}}
    static void clearUnused(File directory){
        File[] children=directory.listFiles();if(children==null)return;
        for(File child:children){
            if(child.isDirectory())clearUnused(child);
            synchronized(ACTIVE){String path=child.getAbsolutePath();boolean used=false;
                for(String active:ACTIVE.keySet())if(path.equals(active)||path.startsWith(active+".")||active.startsWith(path+File.separator)){used=true;break;}
                if(!used)child.delete();
            }
        }
    }
    private ChatMediaFiles(){}
}

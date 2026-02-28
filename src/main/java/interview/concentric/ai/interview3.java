package interview.concentric.ai;


import java.util.List;

public class interview3 {



    public static void main(String[] args) {

    }

//    L1.      l1
//    l2.0       L2.1
//               L2.0
//
//    L3        L4
//            HashSet(L2.0, 2),L3, 3, L2.1,2, L4,3),

    public static  boolean findConflict (List<Line> file1,List<Line> file2){
        int f1Start =0, f2Start=0;
        while (f1Start<file1.size() && f2Start<file2.size()){
            if(file1.get(f1Start).content.trim().compareTo(file2.get(f2Start).content.trim())==0){
                f1Start++;
                f2Start++;
            }else if(file1.get(f1Start).content.trim().isEmpty() && !file2.get(f2Start).content.trim().isEmpty()){
                f2Start++;
            }else if(!file1.get(f1Start).content.trim().isEmpty() && file2.get(f2Start).content.trim().isEmpty()){
                f1Start++;
            }else if(file1.get(f1Start).content.trim().isEmpty() && file2.get(f2Start).content.trim().isEmpty()){
                f1Start++;
                f2Start++;
            }else{
                return true;
            }
        }

        return false;
    }

}


/****
 *
 * GIt version control
 *
 * FR:
 *  It should store commits history
 *  Branch creation/Update/Deletion
 *  Commits
 *  Conflicts detection
 *
 *  commmit Number: X commits
 *  x, y commits;
 *
 *  1,2,3,4
 *
 *
 *  Commit {
 *      id,
 *      owner,
 *      timestamp,
 *      message,
 *      List<Commit> previousCommit,
 *  }
 *
 *
 *  Branch{
 *      Commit: latestCommit,
 *      name,
 *      owner,
 *      timestamp
 *  }
 *
 *
 *  Edits {
 *      id,
 *      commitid,
 *      file,
 *  }
 *
 *  File{
 *      name,
 *      metata,
 *      md5sum,
 *      commit
 *  }
 *
 *  Fversion1.  Fversion2
 *       seg1
 *  seg2        seg3
 *
 */







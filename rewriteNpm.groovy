import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.request.Request

/**
 * Create a virtual symlink for locally published scoped packages from incorrect pathing
 * @[scope]/[artifact]/-/@[scope]/[artifact]-[version].tgz to @[scope]/[artifact]/-/[artifact]-[version].tgz
 * Tested against virtual resolution - will exit once correct path existence is detected, either it already exists or after successful creation
 * in the local. It exits after the first local with correct path existence done to reduce checks.
 * If the user does not have permission to deploy, it will attempt to check the next repository. If the user ONLY has read permissions, then 
 * this plugin will not be useful.
 * In this example only requests containing 'npm' in the repo name is working.
 *
 * @author loreny
 * @since 08/25/2022
 */

download {
    beforeDownloadRequest { Request request, RepoPath path ->
        if (path.repoKey.contains('npm') && path.path.startsWith('@') && path.path.endsWith('.tgz')) {
            //check if repo is virtual, get local for artifact,
            rc = repositories.getRepositoryConfiguration(path.repoKey)

            if (rc.getType() != "virtual" ) {
                log.info "$path.repoKey is not a npm virtual, exiting"
                return
            }

            //check if there are alraedy two @s, exit if so
            def doubleAt = (path.path =~ /@/)
            if (doubleAt.size() > 1) {
                log.info "already has 2 @s, nothing to do here, exiting"
                return
            }

            // Find correct path @types/immutablethree/-/immutablethree-3.8.1.tgz vs existing path @types/immutablethree/-/@types/immutablethree-3.8.1.tgz
            String pkgFullName = request.getRepoPath().getName()
            String[] matcher;
            matcher = path.path.split('/')           
            String originalPath = matcher[0] + '/' + matcher[1] + '/-/' + matcher[0] + '/'+ pkgFullName
            log.info "Transforming NPM download from ${path.path} to ${originalPath}"
            
            localRepos = rc.getRepositories()
            log.debug "npm repos in $path.repoKey found:${localRepos}"
            for (repo in localRepos) {
                rcl = repositories.getRepositoryConfiguration(repo)
                if (rcl.getType() != "local" ) {
                    log.info "$repo is not a npm local, exiting"
                    //switch return to continue if you want to continue checking after remote detected. default artifactory behaviour is to check all locals before remotes.
                    return
                }
                correctExists = repositories.exists(RepoPathFactory.create(repo, path.path))
                doubleExists = repositories.exists(RepoPathFactory.create(repo, originalPath))
                log.debug "$repo:$path.path path exists:$correctExists, double: $doubleExists"
            
                if (doubleExists && !correctExists) {
                    try {           
                        correctPath = RepoPathFactory.create(repo, path.path)
                        originalRepoPath = RepoPathFactory.create(repo, originalPath)
                        log.info "$repo:attempting copy to $correctPath"
                        //check if user has permissions to deploy to this repo, otherwise move onto next 
                        if (!security.canDeploy(correctPath)) {
                            log.info "no permissions, try next repo"
                            continue
                        }
                        repositories.copy(originalRepoPath, correctPath)
                        log.debug("Copied artifact $originalRepoPath to $correctPath")
                        return
                    } catch (Exception e) {
                        log.warn("Unable to copy artifact $originalRepoPath to $correctPath: " + e)
                    }
                } else {
                    log.debug "$repo:no need to copy"
                    if (correctExists && doubleExists) {
                        log.debug "both files already exist in $repo"
                        return
                    } else if (!correctExists && !correctExists) {
                        log.debug "neither files exist in $repo"
                    } else {
                        log.debug "correct single @ path already exists in $repo"
                        return
                    }
                }
            }
        }
    }
}

# SubCreator SpongeForge Build Script
#
#!/usr/bin/env bash
if [[ -z "$mcf_version" ]] || [[ -z "$sp_version" ]]
  then
    echo ERROR: No Build Version Supplied
    rm -Rf "$0"
    exit 1
fi
function __DL() {
    if [[ -x "$(command -v wget)" ]]; then
        wget -O "$1" "$2"; return $?
    else
        curl -o "$1" "$2"; return $?
    fi
}
echo Downloading the Minecraft Forge Installer...
__DL "forge-$mcf_version-installer.jar" "http://files.minecraftforge.net/maven/net/minecraftforge/forge/$mcf_version/forge-$mcf_version-installer.jar"; __RETURN=$?
if [[ $__RETURN -eq 0 ]]; then
    echo Installing Minecraft Forge...
    java -jar "forge-$mcf_version-installer.jar" --installServer; __RETURN=$?
    if [[ $__RETURN -eq 0 ]]; then
        echo Cleaning Up...
        if [[ -f "Forge.jar" ]]; then
            if [[ -f "Forge.old.jar.x" ]]; then
                rm -Rf Forge.old.jar.x
            fi
            mv Forge.jar Forge.old.jar.x
        fi
        if [[ ! -d "mods" ]]; then
            mkdir mods
        fi
        rm -Rf "forge-$mcf_version-installer.jar"
        rm -Rf "forge-$mcf_version-installer.jar.log"
        mv -f "forge-$mcf_version-universal.jar" Forge.jar
        echo Downloading SpongeForge...
        if [[ -f "mods/Sponge.jar" ]]; then
            if [[ -f "mods/Sponge.old.jar.x" ]]; then
                rm -Rf mods/Sponge.old.jar.x
            fi
            mv mods/Sponge.jar mods/Sponge.old.jar.x
        fi
        __DL mods/Sponge.jar "https://repo.spongepowered.org/maven/org/spongepowered/spongeforge/$sp_version/spongeforge-$sp_version.jar"; __RETURN=$?
        if [[ $__RETURN -eq 0 ]]; then
            echo Cleaning Up...
            rm -Rf "$0"
            exit 0
        else
            echo ERROR: Failed downloading Sponge. Is MinecraftForge.net down?
            if [[ -f "mods/Sponge.old.jar.x" ]]; then
                if [[ -f "mods/Sponge.jar" ]]; then
                    rm -Rf mods/Sponge.jar
                fi
                mv mods/Sponge.old.jar.x mods/Sponge.jar
            fi
            if [[ -f "Forge.old.jar.x" ]]; then
                if [[ -f "Forge.jar" ]]; then
                    rm -Rf Forge.jar
                fi
                mv Forge.old.jar.x Forge.jar
            fi
            rm -Rf "$0"
            exit 5
        fi
    else
        echo ERROR: The Installer exited with an error. Please try again
        rm -Rf "forge-$mcf_version-installer.jar"
        rm -Rf "forge-$mcf_version-installer.jar.log"
        rm -Rf "$0"
        exit 4
    fi
else
    echo ERROR: Failed downloading Forge. Is MinecraftForge.net down?
    rm -Rf "$0"
    exit 3
fi
exit 2
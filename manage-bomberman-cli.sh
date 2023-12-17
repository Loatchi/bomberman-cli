
used_command=$1

if [ "$used_command" == "install" ]; then

  path_to_code_zip=$(realpath "$2")

  if [ "$path_to_code_zip" == "" ]; then
      echo "You must provide a path to your code.zip file."
      exit 1
  fi

  if [ ! -f "$path_to_code_zip" ]; then
      echo "$path_to_code_zip does not exist."
      exit 1
  fi


  if [ "$EUID" -ne 0 ]; then
    while true; do
  		read -p "You are going to install bomberman-cli without root, you won't have autocompletion. Are you sure ? [y/N]" yn
  		case $yn in
  		[Yy]* ) break;;
  		[Nn]* ) exit ;;
  		* ) echo "Please answer yes or no.";;
  		esac
  	done
  fi

	git clone https://github.com/Loatchi/bomberman-cli.git
  cd ./bomberman-cli
  unzip "$path_to_code_zip" "level*" -d ./src/main/resources/levels/
  unzip "$path_to_code_zip" "bomberman*" -d ./src/main/resources/

  # installing gradle is annoying on most systems
  wget "https://services.gradle.org/distributions/gradle-8.5-bin.zip"
  unzip "gradle-8.5-bin.zip"
  ./gradle-8.5/bin/gradle jar

  if [ "$EUID" -ne 0 ]; then
      mv ./build/libs/bomberman_cli*.jar ../
      echo "Properly installed non-root bomberman-cli."
      echo "You can now run it by typing 'java -jar bomberman.jar' in a shell."
  else
      mkdir --parents /usr/local/share/bomberman-cli/
      mv ./build/libs/bomberman_cli*.jar /usr/local/share/bomberman-cli/bomberman-cli.jar
      mv ./bomberman-cli.sh /usr/local/bin/bomberman-cli
      chmod +x /usr/local/bin/bomberman-cli

      install_auto_completion=0

      while true; do
        read -p "Do you want to install bash completion ? [y/N]" yn
        case $yn in
        [Yy]* ) install_auto_completion=1;break;;
        [Nn]* ) install_auto_completion=0;break;;
        * ) echo "Please answer yes or no.";;
        esac
      done

      if [ "$install_auto_completion" -eq 1 ]; then
          mkdir --parents /usr/share/bash-completion/completions/
          bomberman-cli --generate-completion=bash > /usr/share/bash-completion/completions/bomberman-cli

          mkdir --parents /usr/share/zsh/site-functions
          bomberman-cli --generate-completion=zsh > /usr/share/zsh/site-functions/_bomberman-cli

          mkdir --parents /usr/share/fish/vendor_completions.d/
          bomberman-cli --generate-completion=fish > /usr/share/fish/vendor_completions.d/bomberman-cli.fish

          echo "Properly installed completion."
      fi

      echo "Properly installed bomberman-cli."
      echo "You can now run it by typing 'bomberman-cli' in a shell."
  fi

  # shellcheck disable=SC2103
  cd ..
  rm -rf bomberman-cli

elif [ "$used_command" == "uninstall" ]; then

    if [ "$EUID" -ne 0 ]; then
        echo "Please run uninstall as root."
        exit 1
    fi

  rm /usr/share/bash-completion/completions/bomberman-cli
  rm /usr/share/zsh/site-functions/_bomberman-cli
  rm /usr/share/fish/vendor_completions.d/bomberman-cli.fish

  rm /usr/local/bin/bomberman-cli
  rm -r /usr/local/share/bomberman-cli

  echo "Properly removed bomberman-cli."
else
	echo "Wrong command, aborting."
	exit 1
fi
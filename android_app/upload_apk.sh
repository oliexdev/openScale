  #go to home and setup git  
  cd $HOME
  git config --global user.email "olie.xdev@googlemail.com"
  git config --global user.name "oliexdev" 

  #clone the repository
  git clone --quiet --branch=master  https://oliexdev:$GITHUB_API_KEY@github.com/oliexdev/openScale  master > /dev/null

  #copy generated apk from build folder to repository
  cp app/build/outputs/apk/app-debug.apk $HOME/master/openScale-dev-build.apk

  #go into repository 
  cd master

  #add, commit and push apk file
  git add -f openScale-dev-build.apk
  git commit -m "openScale dev build $TRAVIS_BUILD_NUMBER by Travis CI [skip ci]" openScale-dev-build.apk
  git push -fq origin master > /dev/null
  echo -e "Done\n"

cmake_minimum_required(VERSION 3.22.1)
project(mosaic_eink_region C)
add_library(mosaic_eink_region SHARED eink_region.c)
target_compile_options(mosaic_eink_region PRIVATE -O2 -Wall -Wextra -Werror)
